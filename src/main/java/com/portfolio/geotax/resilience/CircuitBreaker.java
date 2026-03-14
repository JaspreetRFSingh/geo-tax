package com.portfolio.geotax.resilience;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * A thread-safe Circuit Breaker implementing the classic three-state machine.
 *
 * LEARNING NOTE — What is a Circuit Breaker and why does Uber need it?
 * Imagine the Tax service calls a remote Rules Service to fetch jurisdiction
 * rules. If that Rules Service is down or slow (e.g., a Redis node failed),
 * WITHOUT a circuit breaker, every tax calculation would hang waiting for a
 * timeout (say, 5 seconds). With 50,000 rides/minute, that's catastrophic.
 *
 * The Circuit Breaker is an electrical analogy:
 *
 *   CLOSED (normal) ──[N failures]──→ OPEN (fail-fast)
 *       ↑                                    │
 *       └──[probe succeeds]── HALF-OPEN ←───[timeout]
 *
 * States:
 *   CLOSED    → All calls go through. Track failures.
 *   OPEN      → Fail immediately (no call made). Give dependency time to recover.
 *   HALF_OPEN → Let ONE probe call through. If it succeeds → CLOSED. If not → OPEN.
 *
 * LEARNING NOTE — AtomicInteger / AtomicReference for lock-free thread safety
 * Using 'synchronized' would create a bottleneck — every thread acquiring the
 * lock. AtomicInteger uses CPU-level CAS (Compare-And-Swap) instructions which
 * are non-blocking. This is crucial for a high-throughput system.
 *
 * This is the same pattern Netflix's Hystrix (now Resilience4j) implements.
 */
public class CircuitBreaker {

    private static final Logger log = Logger.getLogger(CircuitBreaker.class.getName());

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String name) {
            super("Circuit breaker '" + name + "' is OPEN — fast-failing request");
        }
    }

    private final String name;
    private final int failureThreshold;       // open after this many failures
    private final Duration openStateDuration; // how long to stay OPEN before probing
    private final Clock clock;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount   = new AtomicInteger(0);
    private final AtomicLong openedAt          = new AtomicLong(0);
    // Guards HALF_OPEN so only ONE probe call goes through
    private final AtomicInteger halfOpenProbes = new AtomicInteger(0);

    public CircuitBreaker(String name, int failureThreshold, Duration openStateDuration) {
        this(name, failureThreshold, openStateDuration, Clock.systemUTC());
    }

    public CircuitBreaker(String name, int failureThreshold, Duration openStateDuration, Clock clock) {
        this.name               = name;
        this.failureThreshold   = failureThreshold;
        this.openStateDuration  = openStateDuration;
        this.clock              = clock;
    }

    /**
     * Execute the given supplier through the circuit breaker.
     * Throws CircuitBreakerOpenException if the circuit is OPEN.
     */
    public <T> T execute(Supplier<T> supplier) {
        State current = currentState();

        if (current == State.OPEN) {
            throw new CircuitBreakerOpenException(name);
        }

        if (current == State.HALF_OPEN) {
            // Only let one probe through
            if (!halfOpenProbes.compareAndSet(0, 1)) {
                throw new CircuitBreakerOpenException(name + " (half-open probe in progress)");
            }
        }

        try {
            T result = supplier.get();
            onSuccess();
            return result;
        } catch (Exception ex) {
            onFailure();
            throw ex;
        }
    }

    private State currentState() {
        State s = state.get();
        if (s == State.OPEN) {
            long openedAtMs = openedAt.get();
            Instant reopenTime = Instant.ofEpochMilli(openedAtMs).plus(openStateDuration);
            if (clock.instant().isAfter(reopenTime)) {
                // Transition OPEN → HALF_OPEN for probing
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    halfOpenProbes.set(0);
                    log.info("[CircuitBreaker:" + name + "] OPEN → HALF_OPEN (probing)");
                }
                return State.HALF_OPEN;
            }
        }
        return state.get();
    }

    private void onSuccess() {
        State s = state.get();
        if (s == State.HALF_OPEN) {
            // Recovery confirmed
            if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                failureCount.set(0);
                halfOpenProbes.set(0);
                log.info("[CircuitBreaker:" + name + "] HALF_OPEN → CLOSED (recovered)");
            }
        } else {
            // Reset failure streak on any success
            failureCount.set(0);
        }
    }

    private void onFailure() {
        State s = state.get();
        if (s == State.HALF_OPEN) {
            // Probe failed — back to OPEN
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openedAt.set(clock.millis());
                halfOpenProbes.set(0);
                log.warning("[CircuitBreaker:" + name + "] HALF_OPEN → OPEN (probe failed)");
            }
            return;
        }

        int failures = failureCount.incrementAndGet();
        log.warning("[CircuitBreaker:" + name + "] Failure #" + failures);

        if (failures >= failureThreshold) {
            if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                openedAt.set(clock.millis());
                log.severe("[CircuitBreaker:" + name + "] CLOSED → OPEN after " + failures + " failures");
            }
        }
    }

    public State getState() { return state.get(); }
    public int getFailureCount() { return failureCount.get(); }
    public String getName() { return name; }
}
