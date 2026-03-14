package com.portfolio.geotax.resilience;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

/**
 * Retry with exponential backoff and jitter.
 *
 * LEARNING NOTE — Why Exponential Backoff + Jitter?
 * Naive retry: retry immediately on failure. Problem: if a service is
 * struggling under load, all clients retrying at the same time creates a
 * "thundering herd" — the service gets hammered just as it's trying to recover.
 *
 * Exponential backoff: each retry waits longer (baseDelay * 2^attempt).
 *   attempt 0: wait 100ms
 *   attempt 1: wait 200ms
 *   attempt 2: wait 400ms
 *
 * Jitter: add randomness to the wait time. This staggers retries across
 * thousands of clients so they don't all hit the service at the same moment.
 * AWS, Google, and Netflix all recommend this pattern for distributed systems.
 *
 * FORMULA: delay = min(cap, base * 2^attempt) * random(0.5, 1.0)
 *
 * LEARNING NOTE — Callable vs Runnable vs Supplier
 * Callable<T> is used here because:
 * 1. It returns a result (unlike Runnable)
 * 2. It throws checked exceptions (unlike Supplier<T>)
 * This makes it the right contract for operations that can fail.
 */
public class RetryWithBackoff {

    private static final Logger log = Logger.getLogger(RetryWithBackoff.class.getName());

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;

    public RetryWithBackoff(int maxAttempts, Duration baseDelay, Duration maxDelay) {
        this.maxAttempts = maxAttempts;
        this.baseDelay   = baseDelay;
        this.maxDelay    = maxDelay;
    }

    public <T> T execute(String operationName, Callable<T> operation) throws Exception {
        Exception lastException = null;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                T result = operation.call();
                if (attempt > 0) {
                    log.info("[Retry:" + operationName + "] Succeeded on attempt " + (attempt + 1));
                }
                return result;
            } catch (Exception ex) {
                lastException = ex;
                if (attempt < maxAttempts - 1) {
                    long delayMs = computeDelayMs(attempt);
                    log.warning(String.format("[Retry:%s] Attempt %d failed (%s). Retrying in %dms...",
                            operationName, attempt + 1, ex.getMessage(), delayMs));
                    Thread.sleep(delayMs);
                }
            }
        }

        throw new RuntimeException(
            "Operation '" + operationName + "' failed after " + maxAttempts + " attempts",
            lastException);
    }

    private long computeDelayMs(int attempt) {
        // Exponential: base * 2^attempt
        long exponential = (long) (baseDelay.toMillis() * Math.pow(2, attempt));
        // Cap at maxDelay
        long capped = Math.min(exponential, maxDelay.toMillis());
        // Jitter: multiply by random in [0.5, 1.0]
        double jitter = 0.5 + Math.random() * 0.5;
        return (long) (capped * jitter);
    }
}
