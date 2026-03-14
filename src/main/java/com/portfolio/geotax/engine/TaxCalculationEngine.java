package com.portfolio.geotax.engine;

import com.portfolio.geotax.model.*;
import com.portfolio.geotax.model.TaxCalculationResult.TaxLineItem;
import com.portfolio.geotax.provider.JurisdictionRuleProvider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Core engine that orchestrates tax calculation for a ride transaction.
 *
 * LEARNING NOTE — The Engine's job
 * The engine is responsible for:
 *   1. Fetching applicable rules for each jurisdiction (with fault tolerance)
 *   2. Filtering rules by time-validity and ride type
 *   3. Computing tax amounts precisely
 *   4. Building an auditable result
 *
 * It does NOT know about HTTP, databases, or caching — those are provider
 * concerns. This separation keeps the engine testable and focused.
 *
 * LEARNING NOTE — CompletableFuture for parallel jurisdiction lookups
 * For inter-jurisdiction rides (e.g., Hyderabad → Pune crossing IN-TS and IN-MH),
 * we can fetch rules for BOTH jurisdictions in parallel rather than sequentially.
 *
 * Sequential:  [fetch IN-TS] → [fetch IN-MH] = 200ms total
 * Parallel:    [fetch IN-TS] │               = 100ms total
 *              [fetch IN-MH] │
 *
 * At Uber's scale (millions of rides), this latency difference is enormous.
 */
public class TaxCalculationEngine {

    private static final Logger log = Logger.getLogger(TaxCalculationEngine.class.getName());

    private final JurisdictionRuleProvider ruleProvider;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final ExecutorService executorService;

    public TaxCalculationEngine(JurisdictionRuleProvider ruleProvider) {
        this.ruleProvider = ruleProvider;

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(3)
            .minimumNumberOfCalls(3)
            .failureRateThreshold(100f)          // open when all 3 calls in window fail
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(1)
            .build();
        this.circuitBreaker = CircuitBreakerRegistry.of(cbConfig)
            .circuitBreaker("jurisdiction-rule-provider");

        RetryConfig retryConfig = RetryConfig.custom()
            .maxAttempts(3)
            .intervalFunction(attempt -> {
                long exponential = (long) (100 * Math.pow(2, attempt));
                long capped = Math.min(exponential, 2000L);
                double jitter = 0.5 + Math.random() * 0.5;
                return (long) (capped * jitter);
            })
            .build();
        this.retry = RetryRegistry.of(retryConfig).retry("jurisdiction-rule-provider");
        // Named thread pool for observability
        this.executorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(),
            r -> {
                Thread t = new Thread(r, "tax-engine-worker");
                t.setDaemon(true);
                return t;
            }
        );
    }

    /**
     * Main entry point: calculate taxes for a ride transaction.
     *
     * LEARNING NOTE — Result<T> vs throwing exceptions
     * We return a Result wrapper rather than throwing exceptions for business
     * errors. Exceptions should be for unexpected situations. "No rules found"
     * or "provider temporarily unavailable" are expected conditions that the
     * caller should handle gracefully.
     */
    public TaxCalculationResult calculate(TaxCalculationRequest request) {
        log.info("Calculating tax for transaction: " + request.getTransactionId());

        // Determine all jurisdictions involved
        Set<String> jurisdictions = new LinkedHashSet<>();
        jurisdictions.add(request.getOriginJurisdiction());
        if (request.isInterJurisdiction()) {
            jurisdictions.add(request.getDestinationJurisdiction());
        }

        // Fetch rules for all jurisdictions in parallel
        Map<String, List<TaxRule>> rulesByJurisdiction =
            fetchRulesInParallel(new ArrayList<>(jurisdictions));

        // Build line items
        List<TaxLineItem> lineItems = new ArrayList<>();
        List<String> appliedRuleIds = new ArrayList<>();

        for (Map.Entry<String, List<TaxRule>> entry : rulesByJurisdiction.entrySet()) {
            String jurisdiction = entry.getKey();
            List<TaxRule> rules = entry.getValue();

            // Filter: only rules active at the time of the ride, applicable to ride type
            List<TaxRule> applicableRules = rules.stream()
                .filter(r -> r.isActiveAt(request.getRideCompletedAt()))
                .filter(r -> r.appliesTo(request.getRideType()))
                .collect(Collectors.toList());

            log.info(String.format("Jurisdiction %s: %d/%d rules applicable",
                jurisdiction, applicableRules.size(), rules.size()));

            for (TaxRule rule : applicableRules) {
                BigDecimal taxAmount = computeTaxAmount(request.getBaseFare(), rule.getRate());
                lineItems.add(new TaxLineItem(
                    rule.getRuleId(),
                    rule.getTaxType(),
                    rule.getJurisdictionCode(),
                    request.getBaseFare(),
                    rule.getRate(),
                    taxAmount
                ));
                appliedRuleIds.add(rule.getRuleId() + "@v" + rule.getVersion());
            }
        }

        return new TaxCalculationResult(
            request.getTransactionId(),
            request.getBaseFare(),
            lineItems,
            appliedRuleIds,
            false
        );
    }

    /**
     * Fetch rules for multiple jurisdictions in parallel using CompletableFuture.
     *
     * LEARNING NOTE — CompletableFuture.allOf()
     * We fire off async tasks for each jurisdiction, then wait for ALL of them
     * to complete with allOf(). If any fails (provider exception), we log and
     * return an empty rule set for that jurisdiction — graceful degradation.
     *
     * Alternative: use stream().parallel() — but that uses the common ForkJoinPool
     * which is shared across the JVM. For I/O-bound tasks (network/DB calls),
     * using your own ExecutorService gives better control and doesn't starve
     * other parts of the application.
     */
    private Map<String, List<TaxRule>> fetchRulesInParallel(List<String> jurisdictions) {
        Map<String, CompletableFuture<List<TaxRule>>> futures = new LinkedHashMap<>();

        for (String jCode : jurisdictions) {
            CompletableFuture<List<TaxRule>> future = CompletableFuture.supplyAsync(
                () -> fetchRulesWithResilience(jCode),
                executorService
            );
            futures.put(jCode, future);
        }

        // Wait for all futures
        Map<String, List<TaxRule>> result = new LinkedHashMap<>();
        for (Map.Entry<String, CompletableFuture<List<TaxRule>>> entry : futures.entrySet()) {
            try {
                result.put(entry.getKey(), entry.getValue().get(5, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                log.severe("Timeout fetching rules for jurisdiction: " + entry.getKey());
                result.put(entry.getKey(), Collections.emptyList());
            } catch (Exception e) {
                log.severe("Error fetching rules for jurisdiction: " + entry.getKey() + " — " + e.getMessage());
                result.put(entry.getKey(), Collections.emptyList());
            }
        }
        return result;
    }

    /**
     * Wrap provider call with circuit breaker + retry.
     *
     * LEARNING NOTE — Layered resilience
     * Circuit breaker wraps the retry. Why this order?
     * - Retry handles TRANSIENT failures (brief network hiccup)
     * - Circuit breaker handles SUSTAINED failures (service is down)
     * If the circuit is OPEN, we don't even attempt the retry — fast-fail.
     * This protects both the caller and the downstream service.
     */
    private List<TaxRule> fetchRulesWithResilience(String jurisdictionCode) {
        try {
            return circuitBreaker.executeSupplier(() -> {
                try {
                    return retry.executeCallable(
                        () -> ruleProvider.getRulesForJurisdiction(jurisdictionCode)
                    );
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (CallNotPermittedException e) {
            log.warning("Circuit breaker open for jurisdiction: " + jurisdictionCode + " — using empty rules");
            return Collections.emptyList();
        }
    }

    /**
     * Precise tax computation.
     *
     * LEARNING NOTE — Scale and RoundingMode
     * setScale(4, ...) during multiplication preserves intermediate precision.
     * setScale(2, HALF_EVEN) in the final result rounds to the nearest paisa/cent.
     *
     * 100.00 * 0.05 = 5.0000 → rounds to 5.00 ✓
     * 333.33 * 0.18 = 59.9994 → HALF_EVEN rounds to 60.00 ✓
     */
    private BigDecimal computeTaxAmount(BigDecimal baseFare, BigDecimal rate) {
        return baseFare
            .multiply(rate)
            .setScale(4, RoundingMode.HALF_EVEN)  // intermediate precision
            .setScale(2, RoundingMode.HALF_EVEN); // final rounding
    }

    public void shutdown() {
        executorService.shutdown();
    }
}
