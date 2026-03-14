package com.portfolio.geotax.service;

import com.portfolio.geotax.engine.TaxCalculationEngine;
import com.portfolio.geotax.model.TaxCalculationRequest;
import com.portfolio.geotax.model.TaxCalculationResult;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/**
 * Service layer that wraps the engine with idempotency and metrics.
 *
 * LEARNING NOTE — Idempotency in FinTech
 * Idempotency means: calling the same operation multiple times has the same
 * effect as calling it once. This is CRITICAL for payment/tax systems.
 *
 * Why? Network failures are normal. The client (Rides service) sends a
 * tax calculation request. The Tax service processes it, but the response
 * gets lost. The client retries. WITHOUT idempotency, the same ride gets
 * taxed twice — a serious billing bug.
 *
 * The solution: use the transactionId as an idempotency key. If we've already
 * calculated tax for this transaction, return the cached result.
 *
 * Real Uber implementation: idempotency keys stored in a distributed cache
 * (Redis) with a TTL, so they survive service restarts.
 *
 * LEARNING NOTE — LongAdder vs AtomicLong for counters
 * LongAdder is designed for high-contention counting (many threads incrementing
 * simultaneously). It maintains multiple "cells" and sums them on read, reducing
 * CAS contention compared to AtomicLong. For metrics/counters under heavy write
 * load, LongAdder wins on throughput.
 */
public class TaxCalculationService {

    private static final Logger log = Logger.getLogger(TaxCalculationService.class.getName());

    private final TaxCalculationEngine engine;

    // In production: replace with Redis/Memcached with TTL
    // Key: transactionId, Value: cached result
    private final Map<String, TaxCalculationResult> idempotencyCache = new ConcurrentHashMap<>();

    // Metrics
    private final LongAdder totalRequests     = new LongAdder();
    private final LongAdder cacheHits         = new LongAdder();
    private final LongAdder engineCalculations = new LongAdder();
    private final LongAdder errors            = new LongAdder();

    public TaxCalculationService(TaxCalculationEngine engine) {
        this.engine = engine;
    }

    /**
     * Calculate taxes for a ride — idempotent on transactionId.
     */
    public TaxCalculationResult calculateTax(TaxCalculationRequest request) {
        totalRequests.increment();

        // Idempotency check
        TaxCalculationResult cached = idempotencyCache.get(request.getTransactionId());
        if (cached != null) {
            log.info("Cache hit for transactionId: " + request.getTransactionId());
            cacheHits.increment();
            return cached;
        }

        try {
            TaxCalculationResult result = engine.calculate(request);
            idempotencyCache.put(request.getTransactionId(), result);
            engineCalculations.increment();
            log.info("Tax calculated: " + result);
            return result;

        } catch (Exception ex) {
            errors.increment();
            log.severe("Tax calculation failed for txn " + request.getTransactionId() + ": " + ex.getMessage());
            throw ex;
        }
    }

    /**
     * Process a batch of requests concurrently.
     *
     * LEARNING NOTE — Why batch endpoints?
     * Uber processes end-of-trip events in bulk — e.g., at night, reconciliation
     * jobs re-calculate taxes for thousands of corrected rides. A batch API
     * lets callers send many requests in one call, reducing network overhead.
     */
    public List<TaxCalculationResult> calculateBatch(List<TaxCalculationRequest> requests) {
        return requests.parallelStream()
            .map(this::calculateTax)
            .toList();
    }

    public ServiceMetrics getMetrics() {
        return new ServiceMetrics(
            totalRequests.sum(),
            cacheHits.sum(),
            engineCalculations.sum(),
            errors.sum()
        );
    }

    public record ServiceMetrics(
        long totalRequests,
        long cacheHits,
        long engineCalculations,
        long errors
    ) {
        public double cacheHitRate() {
            return totalRequests == 0 ? 0.0 : (double) cacheHits / totalRequests;
        }

        @Override public String toString() {
            return String.format(
                "Metrics[total=%d | hits=%d | calcs=%d | errors=%d | hitRate=%.1f%%]",
                totalRequests, cacheHits, engineCalculations, errors, cacheHitRate() * 100);
        }
    }
}
