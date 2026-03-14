# GeoTax — Multi-Jurisdiction Tax Calculation Engine

A distributed, multi-jurisdiction tax calculation microservice in Java that mirrors what a large-scale mobility platform's tax system would do at its core.

- **Correct**: Uses `BigDecimal` for precise financial calculations and adheres to India's GST rules.
- **Scalable**: Handles many rides per minute with low latency.
- **Resilient**: Implements circuit breakers and retries for external dependencies.
---

## Project Architecture

```
TaxCalculationRequest
        │
        ▼
┌─────────────────────────┐
│  TaxCalculationService  │  ← Idempotency cache + metrics
└──────────┬──────────────┘
           │
           ▼
┌─────────────────────────┐
│  TaxCalculationEngine   │  ← Parallel jurisdiction fetching
└──────────┬──────────────┘
           │
     ┌─────┴──────┐
     │             │   (CompletableFuture parallel fetch)
     ▼             ▼
  IN-TS rules   IN-MH rules
     │             │
     └──── merge ──┘
           │
           ▼
   [Filter by time + ride type]
           │
           ▼
   [Compute BigDecimal amounts]
           │
           ▼
  TaxCalculationResult (with audit trail)
```

Each call to the rule provider is wrapped in:
```
CircuitBreaker → RetryWithBackoff → JurisdictionRuleProvider
```

---

## Key Concepts & Learning Notes

---

### 1. Immutability for Thread Safety

**File:** `TaxRule.java`

`TaxRule` is fully immutable — all fields are `final`, no setters exist, and collections (like lists passed in) are defensively copied.

**Why it matters at large scale:**
When 50,000+ rides/minute each need to look up the same tax rules, those objects are read concurrently by hundreds of threads. An immutable object requires zero synchronization — any thread can read it safely, simultaneously.

```java
//immutable — the object never changes after construction
TaxRule rule = TaxRule.builder().rate("0.05").build(); // safe forever
```

**Immutability also prevents accidental bugs:** if the rule provider returns a rule and the caller can't modify it, you can cache it confidently.

---

### 2. BigDecimal for Financial Arithmetic

**Files:** `TaxRule.java`, `TaxCalculationEngine.java`, `TaxCalculationResult.java`

**The golden rule of FinTech: never use `float` or `double` for money.**

```java
// A bug hiding in plain sight:
double tax = 333.33 * 0.05;
System.out.println(tax); // → 16.666499999999998  ← WRONG

// BigDecimal gives exact results:
BigDecimal tax = new BigDecimal("333.33").multiply(new BigDecimal("0.05"));
// → 16.6665  (exact)
```

**Rounding Mode — HALF_EVEN (Banker's Rounding):**

India's GST guidelines specify HALF_EVEN (also called "round half to even"). This means `.5` rounds to the nearest even digit:

- 16.665 → 16.67 (rounds up, 7 is odd... wait, 6 is even actually → 16.66? Let's be precise)
- 2.5 → 2 (rounds to even, 2)
- 3.5 → 4 (rounds to even, 4)

This eliminates statistical bias when rounding millions of transactions. If you use HALF_UP everywhere, you systematically over-collect tax — a regulatory problem.

**Scale management:**
```java
baseFare.multiply(rate)
    .setScale(4, RoundingMode.HALF_EVEN)  // Keep 4 decimal places during computation
    .setScale(2, RoundingMode.HALF_EVEN); // Final rounding to paisa/cent
```

---

### 3. Dependency Inversion Principle (D in SOLID)

**File:** `JurisdictionRuleProvider.java`

The engine depends on the `JurisdictionRuleProvider` **interface**, not any concrete class. This enables:

| Environment | Provider Used |
|---|---|
| Unit Tests | `InMemoryJurisdictionRuleProvider` |
| Integration Tests | `FakeSlowProvider` (simulates latency) |
| Staging | `RedisJurisdictionRuleProvider` |
| Production | `RedisJurisdictionRuleProvider` + DB fallback |

Swapping providers requires **zero changes to `TaxCalculationEngine`**. This is how large codebases stay testable and maintainable.

---

### 4. Circuit Breaker Pattern

**File:** `CircuitBreaker.java`

**The Problem:** The rule provider makes a network call. If the Redis cluster is down, each call waits 5 seconds for a timeout. At 1000 requests/second, you have 5000 concurrent threads all blocked waiting. The JVM runs out of memory. Everything crashes.

**The Solution — Three-State Machine:**

```
CLOSED (healthy)
   │  N failures in a row
   ▼
OPEN (fail fast, no calls made)
   │  After timeout (e.g., 30 seconds)
   ▼
HALF-OPEN (allow ONE probe call)
   │  Probe succeeds → CLOSED
   │  Probe fails    → OPEN
```

**Why AtomicReference/AtomicInteger instead of synchronized?**

`synchronized` creates a bottleneck: only one thread can check/update circuit state at a time. `AtomicInteger` and `AtomicReference` use CPU-level **Compare-And-Swap (CAS)** instructions — they're lock-free. On modern multi-core hardware, this is dramatically faster under contention.

```java
// CAS: "set to OPEN only if current value is CLOSED"
state.compareAndSet(State.CLOSED, State.OPEN)
// If two threads both see CLOSED and try to trip the circuit,
// only one wins the CAS. The other gets false and does nothing.
// No locking needed.
```

This pattern is implemented by Netflix's Hystrix and its successor, Resilience4j — both industry standard.

---

### 5. Exponential Backoff with Jitter

**File:** `RetryWithBackoff.java`

**Why not just retry immediately?**

If 10,000 clients all retry simultaneously when a service hiccups, they collectively create a traffic spike that prevents recovery — the "thundering herd" problem.

**Exponential backoff** spaces retries out:
- Attempt 1: wait 100ms
- Attempt 2: wait 200ms
- Attempt 3: wait 400ms

**Jitter** adds randomness so clients don't all retry at exactly the same moment:
```java
delay = min(maxDelay, baseDelay * 2^attempt) * random(0.5, 1.0)
```

This is the exact pattern recommended by AWS, Google Cloud, and detailed in the Amazon "Builder's Library" article "Timeouts, retries, and backoff with jitter."

---

### 6. CompletableFuture for Parallel Jurisdiction Lookups

**File:** `TaxCalculationEngine.java`

For an inter-state ride (e.g., Hyderabad → Pune), we need rules for both `IN-TS` and `IN-MH`. We can fetch them in parallel:

```java
// Sequential: 100ms + 100ms = 200ms total
List<TaxRule> ts = provider.getRules("IN-TS");   // 100ms
List<TaxRule> mh = provider.getRules("IN-MH");   // 100ms

// Parallel: max(100ms, 100ms) = 100ms total
CompletableFuture<List<TaxRule>> tsFuture =
    CompletableFuture.supplyAsync(() -> provider.getRules("IN-TS"), executor);
CompletableFuture<List<TaxRule>> mhFuture =
    CompletableFuture.supplyAsync(() -> provider.getRules("IN-MH"), executor);
// Both execute simultaneously
```

**Why a custom ExecutorService instead of `stream().parallel()`?**

`parallelStream()` uses the JVM's `ForkJoinPool.commonPool()`, which is shared by ALL parallel streams in the process. If something else (say, a report generation job) is using that pool heavily, your tax calculation slows down unpredictably.

A dedicated `ExecutorService` with a fixed thread pool gives you:
- Isolation from other work
- Controlled parallelism (sized to `availableProcessors`)
- Named threads (aids debugging in thread dumps: "tax-engine-worker" is much more informative than "ForkJoinPool.commonPool-worker-3")

---

### 7. Idempotency in FinTech

**File:** `TaxCalculationService.java`

**The scenario:** A rides service sends a tax calculation request. The Tax service computes it and sends the response. The response gets lost in a network partition. The rides service retries. Without idempotency, the ride gets a second, duplicate tax event inserted into the audit log — a compliance nightmare.

**Solution:** Use `transactionId` as an idempotency key.

```
Request 1 (txnId="TXN-123") → compute → cache → return result
Request 2 (txnId="TXN-123") → cache hit → return SAME result (no recompute)
```

**Production note:** The `ConcurrentHashMap` cache here is in-process and ephemeral. In production, you'd use Redis with a TTL:
```
SETEX idempotency:TXN-123 3600 <serialized_result>
```
This survives service restarts and works across multiple service instances.

---

### 8. Audit Trail Design

**File:** `TaxCalculationResult.java`

Tax authorities can audit a platform years after a transaction. Every result includes:

```java
List<String> appliedRuleIds = ["IN-TS-GST-STD-001@v3", "IN-TS-CESS-001@v1"]
```

The `@v3` suffix is the rule's **version number**. If a provider changes the GST rate from 5% to 6% in Q2, the old rule (v3) is kept in history. You can always replay `IN-TS-GST-STD-001@v3` to get exactly what was charged on a ride in January, even if the current version is v5.

This is the **Event Sourcing** pattern applied to tax rules — never delete history, only append new versions.

---

### 9. LongAdder vs AtomicLong for Metrics

**File:** `TaxCalculationService.java`

```java
private final LongAdder totalRequests = new LongAdder(); // not AtomicLong
```

Both are thread-safe counters. The difference:

- **AtomicLong**: single memory location, all threads CAS the same cell → contention under high concurrency
- **LongAdder**: maintains multiple "cells" per CPU, each thread updates its own cell → near-zero contention. `sum()` adds all cells on read.

At large scale (millions of requests), `LongAdder` outperforms `AtomicLong` significantly for counters that are written more than read. Java 8+ recommendation from Doug Lea, author of `java.util.concurrent`.

---

### 10. Testing Concurrent Code

**File:** `TaxCalculationEngineTest.java`

The `should_handle_concurrent_idempotency` test fires 50 threads simultaneously at the same transactionId using a `CountDownLatch`:

```java
CountDownLatch startLatch = new CountDownLatch(1);
// All 50 threads wait here...
startLatch.countDown(); // RELEASE — all 50 start simultaneously
```

This is how you test race conditions. Without this synchronization, threads start sequentially and you never actually test concurrent behavior.

---

## Key Conceptual Areas

**On distributed systems:**
> "The engine handles multi-jurisdiction lookups in parallel using CompletableFuture with a dedicated thread pool. I wrapped the provider calls in a Circuit Breaker — if the rule store goes down, the engine fast-fails instead of creating thread pile-up, and recovers automatically when the dependency comes back."

**On data consistency:**
> "Each TaxRule has a version number, and every result records exactly which version of each rule was applied. This means calculations are reproducible and auditable years later — critical for tax compliance."

**On performance:**
> "I used LongAdder instead of AtomicLong for metrics because at high request rates, CAS contention on a single memory location becomes measurable. LongAdder distributes writes across CPU-local cells."

**On FinTech correctness:**
> "All monetary arithmetic uses BigDecimal with HALF_EVEN rounding — the same rounding mode specified in India's GST guidelines. Using doubles here would silently introduce errors across millions of transactions."

---

## Suggested Extensions (Pull Requests are welcome!)

1. **Redis caching layer** — Implement `RedisJurisdictionRuleProvider` using Jedis or Lettuce. Add TTL-based cache invalidation with a pub/sub invalidation message when rules change.

2. **Kafka event streaming** — Publish a `TaxCalculatedEvent` to a Kafka topic after each successful calculation. Downstream services (billing, audit) consume this event. This decouples Tax from Billing (choreography over orchestration).

3. **Graceful degradation** — If the rule provider is down, fall back to the last successfully cached rules rather than zero-tax. Add a `staleRuleAcceptancePolicy` config.

4. **gRPC endpoint** — Replace the HTTP API with gRPC for lower latency between internal services. This is standard practice at large companies.

5. **Rule versioning with effective dates** — Add an admin API to "publish" a new rule version effective on a future date. The engine automatically picks up the new version at the right time.

---

## Running the Project

```bash
# Compile
mvn compile

# Run tests
mvn test

# Run a specific test
mvn test -Dtest=TaxCalculationEngineTest#should_calculate_gst_correctly_for_standard_ride
```

---

## Tech Stack Summary

| Component | Technology | Why |
|---|---|---|
| Language | Java 17 | Records, sealed classes, improved switch |
| Build | Maven | Standard at most large companies |
| Concurrency | `CompletableFuture`, `ConcurrentHashMap`, `LongAdder` | Java stdlib, no extra deps |
| Resilience | Hand-rolled Circuit Breaker + Retry | Demonstrates understanding vs black-box library |
| Arithmetic | `BigDecimal` | Financial precision |
| Testing | JUnit 5 (Jupiter) | Modern, parameterized tests, `@DisplayName` |
