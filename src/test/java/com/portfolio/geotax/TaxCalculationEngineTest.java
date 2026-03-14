package com.portfolio.geotax;

import com.portfolio.geotax.engine.TaxCalculationEngine;
import com.portfolio.geotax.model.*;
import com.portfolio.geotax.model.TaxCalculationResult.TaxLineItem;
import com.portfolio.geotax.provider.InMemoryJurisdictionRuleProvider;
import com.portfolio.geotax.provider.JurisdictionRuleProvider;
import com.portfolio.geotax.resilience.CircuitBreaker;
import com.portfolio.geotax.service.TaxCalculationService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LEARNING NOTE — Testing philosophy for distributed systems
 * Unit tests for a tax engine must cover:
 * 1. Correct calculation (happy path)
 * 2. Boundary conditions (rule effective dates, ride type filters)
 * 3. Fault tolerance (circuit breaker, provider failures)
 * 4. Concurrency (idempotency under concurrent duplicate requests)
 * 5. Precision (BigDecimal arithmetic edge cases)
 *
 * Each test method name follows the pattern:
 *   should_[expected behavior]_when_[condition]
 * This makes test failures self-documenting.
 */
class TaxCalculationEngineTest {

    private InMemoryJurisdictionRuleProvider ruleProvider;
    private TaxCalculationEngine engine;
    private TaxCalculationService service;

    @BeforeEach
    void setUp() {
        ruleProvider = new InMemoryJurisdictionRuleProvider();
        engine = new TaxCalculationEngine(ruleProvider);
        service = new TaxCalculationService(engine);
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    // =========================================================================
    // HAPPY PATH TESTS
    // =========================================================================

    @Test
    @DisplayName("Should calculate 5% GST correctly for a standard Hyderabad ride")
    void should_calculate_gst_correctly_for_standard_ride() {
        TaxCalculationRequest request = TaxCalculationRequest.builder()
            .rideId("RIDE-001")
            .baseFare("100.00")
            .originJurisdiction("IN-TS")
            .rideType(TaxRule.RideType.STANDARD)
            .rideCompletedAt(Instant.now())
            .build();

        TaxCalculationResult result = service.calculateTax(request);

        assertAll(
            () -> assertEquals(new BigDecimal("100.00"), result.getBaseFare()),
            () -> assertEquals(new BigDecimal("5.00"), result.getTotalTaxAmount(),
                "5% GST on ₹100 should be ₹5.00"),
            () -> assertEquals(new BigDecimal("105.00"), result.getTotalAmountWithTax()),
            () -> assertFalse(result.getAppliedRuleIds().isEmpty()),
            () -> assertFalse(result.getLineItems().isEmpty())
        );
    }

    @Test
    @DisplayName("Should apply multiple taxes (GST + Cess) for Maharashtra rides")
    void should_apply_gst_and_cess_for_maharashtra_ride() {
        TaxCalculationRequest request = TaxCalculationRequest.builder()
            .rideId("RIDE-002")
            .baseFare("200.00")
            .originJurisdiction("IN-MH")
            .rideCompletedAt(Instant.now())
            .build();

        TaxCalculationResult result = service.calculateTax(request);

        // IN-MH has 5% GST + 1% Cess = 6% total
        assertEquals(2, result.getLineItems().size(), "Should have 2 tax line items (GST + Cess)");
        assertEquals(new BigDecimal("12.00"), result.getTotalTaxAmount(),
            "6% on ₹200 = ₹12.00");

        long gstCount = result.getLineItems().stream()
            .filter(li -> li.taxType() == TaxRule.TaxType.GST).count();
        long cessCount = result.getLineItems().stream()
            .filter(li -> li.taxType() == TaxRule.TaxType.CESS).count();

        assertEquals(1, gstCount, "Should have exactly 1 GST line item");
        assertEquals(1, cessCount, "Should have exactly 1 Cess line item");
    }

    @ParameterizedTest(name = "Base fare {0} should produce tax {1}")
    @CsvSource({
        "100.00,  5.00",
        "333.33,  16.67",   // Tests HALF_EVEN rounding: 333.33 * 0.05 = 16.6665 → 16.67
        "999.99,  50.00",   // 999.99 * 0.05 = 49.9995 → 50.00 (HALF_EVEN)
        "0.01,    0.00",    // Tiny amount rounds to zero
        "10000.00, 500.00"
    })
    @DisplayName("Should round tax amounts using HALF_EVEN (Banker's Rounding)")
    void should_use_half_even_rounding(String baseFare, String expectedTax) {
        TaxCalculationRequest request = TaxCalculationRequest.builder()
            .rideId("RIDE-ROUND-" + baseFare)
            .baseFare(baseFare)
            .originJurisdiction("IN-TS")
            .rideType(TaxRule.RideType.STANDARD)
            .rideCompletedAt(Instant.now())
            .build();

        TaxCalculationResult result = service.calculateTax(request);

        assertEquals(new BigDecimal(expectedTax), result.getTotalTaxAmount(),
            "Expected HALF_EVEN rounded tax of " + expectedTax + " for fare " + baseFare);
    }

    // =========================================================================
    // RULE FILTERING TESTS
    // =========================================================================

    @Test
    @DisplayName("Should not apply expired rules")
    void should_not_apply_expired_rules() {
        // Add an expired rule
        TaxRule expiredRule = TaxRule.builder()
            .ruleId("IN-TS-EXPIRED-001")
            .jurisdictionCode("IN-TS")
            .taxType(TaxRule.TaxType.SURCHARGE)
            .rate("0.02")
            .effectiveFrom(Instant.parse("2020-01-01T00:00:00Z"))
            .effectiveTo(Instant.parse("2021-01-01T00:00:00Z"))  // expired
            .build();
        ruleProvider.addRule(expiredRule);

        TaxCalculationRequest request = TaxCalculationRequest.builder()
            .rideId("RIDE-EXPIRED")
            .baseFare("100.00")
            .originJurisdiction("IN-TS")
            .rideType(TaxRule.RideType.STANDARD)
            .rideCompletedAt(Instant.now())  // after expiry
            .build();

        TaxCalculationResult result = service.calculateTax(request);

        boolean expiredRuleApplied = result.getAppliedRuleIds().stream()
            .anyMatch(id -> id.startsWith("IN-TS-EXPIRED-001"));

        assertFalse(expiredRuleApplied, "Expired rule should not be applied");
    }

    @Test
    @DisplayName("Should only apply ride-type-specific rules to matching rides")
    void should_filter_rules_by_ride_type() {
        // Add an AUTO-specific rule
        TaxRule autoRule = TaxRule.builder()
            .ruleId("IN-KA-GST-AUTO-001")
            .jurisdictionCode("IN-KA")
            .taxType(TaxRule.TaxType.GST)
            .rate("0.05")
            .applicableRideType(TaxRule.RideType.AUTO)
            .effectiveFrom(Instant.parse("2017-07-01T00:00:00Z"))
            .build();
        ruleProvider.addRule(autoRule);

        // STANDARD ride to Karnataka — should NOT apply the AUTO rule
        TaxCalculationRequest standardRequest = TaxCalculationRequest.builder()
            .rideId("RIDE-KA-STD")
            .baseFare("100.00")
            .originJurisdiction("IN-KA")
            .rideType(TaxRule.RideType.STANDARD)
            .rideCompletedAt(Instant.now())
            .build();

        TaxCalculationResult result = service.calculateTax(standardRequest);

        boolean autoRuleApplied = result.getAppliedRuleIds().stream()
            .anyMatch(id -> id.startsWith("IN-KA-GST-AUTO-001"));
        assertFalse(autoRuleApplied, "AUTO rule should not apply to STANDARD ride");
    }

    // =========================================================================
    // IDEMPOTENCY TEST
    // =========================================================================

    @Test
    @DisplayName("Should return identical result for duplicate transactionId (idempotent)")
    void should_be_idempotent_on_duplicate_transaction_id() {
        String txnId = "TXN-IDEMPOTENT-001";

        TaxCalculationRequest request = TaxCalculationRequest.builder()
            .transactionId(txnId)
            .rideId("RIDE-IDEM")
            .baseFare("150.00")
            .originJurisdiction("IN-TS")
            .rideCompletedAt(Instant.now())
            .build();

        TaxCalculationResult first = service.calculateTax(request);
        TaxCalculationResult second = service.calculateTax(request);

        assertSame(first, second, "Second call should return the SAME cached result object");

        TaxCalculationService.ServiceMetrics metrics = service.getMetrics();
        assertEquals(1, metrics.cacheHits(), "Should record exactly 1 cache hit");
        assertEquals(1, metrics.engineCalculations(), "Engine should only compute once");
    }

    @Test
    @DisplayName("Should handle concurrent duplicate requests idempotently")
    void should_handle_concurrent_idempotency() throws InterruptedException {
        String txnId = "TXN-CONCURRENT-001";
        int threadCount = 50;

        TaxCalculationRequest request = TaxCalculationRequest.builder()
            .transactionId(txnId)
            .rideId("RIDE-CONCURRENT")
            .baseFare("200.00")
            .originJurisdiction("IN-MH")
            .rideCompletedAt(Instant.now())
            .build();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<TaxCalculationResult> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    results.add(service.calculateTax(request));
                } catch (Exception e) {
                    // Should not happen
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown(); // Release all threads simultaneously
        doneLatch.await(5, TimeUnit.SECONDS);

        assertEquals(threadCount, results.size());

        // All results should be the same object (from cache after first calculation)
        TaxCalculationResult first = results.get(0);
        results.forEach(r -> assertEquals(first.getTransactionId(), r.getTransactionId()));
        results.forEach(r -> assertEquals(first.getTotalTaxAmount(), r.getTotalTaxAmount()));
    }

    // =========================================================================
    // CIRCUIT BREAKER TEST
    // =========================================================================

    @Test
    @DisplayName("Circuit breaker should open after threshold failures and fast-fail")
    void circuit_breaker_should_open_after_failures() {
        AtomicInteger callCount = new AtomicInteger(0);

        // Mock clock to control time
        Instant[] now = { Instant.now() };
        Clock mockClock = Clock.fixed(now[0], ZoneOffset.UTC);

        CircuitBreaker cb = new CircuitBreaker("test-cb", 3, Duration.ofSeconds(30), mockClock);

        // Cause 3 failures to open the circuit
        for (int i = 0; i < 3; i++) {
            assertThrows(RuntimeException.class, () ->
                cb.execute(() -> { throw new RuntimeException("Simulated failure"); })
            );
        }

        assertEquals(CircuitBreaker.State.OPEN, cb.getState(), "Circuit should be OPEN");

        // Next call should fast-fail with CircuitBreakerOpenException
        assertThrows(CircuitBreaker.CircuitBreakerOpenException.class, () ->
            cb.execute(() -> "should not reach here")
        );
    }

    // =========================================================================
    // AUDIT TRAIL TEST
    // =========================================================================

    @Test
    @DisplayName("Should include rule version in audit trail")
    void should_include_rule_version_in_audit_trail() {
        TaxCalculationRequest request = TaxCalculationRequest.builder()
            .rideId("RIDE-AUDIT")
            .baseFare("100.00")
            .originJurisdiction("IN-TS")
            .rideType(TaxRule.RideType.STANDARD)
            .rideCompletedAt(Instant.now())
            .build();

        TaxCalculationResult result = service.calculateTax(request);

        // Each applied rule ID should include the version (@v3, @v2, etc.)
        result.getAppliedRuleIds().forEach(id ->
            assertTrue(id.contains("@v"), "Audit trail entry should include rule version: " + id)
        );
    }

    @Test
    @DisplayName("Should return empty taxes for unknown jurisdiction")
    void should_return_zero_tax_for_unknown_jurisdiction() {
        TaxCalculationRequest request = TaxCalculationRequest.builder()
            .rideId("RIDE-UNKNOWN")
            .baseFare("100.00")
            .originJurisdiction("XX-UNKNOWN")
            .rideCompletedAt(Instant.now())
            .build();

        TaxCalculationResult result = service.calculateTax(request);

        assertEquals(BigDecimal.ZERO.setScale(2), result.getTotalTaxAmount(),
            "Unknown jurisdiction should yield zero tax");
        assertTrue(result.getLineItems().isEmpty());
    }
}
