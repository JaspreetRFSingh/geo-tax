# GeoTax — High-Level Design

## Problem Statement

Ride-hailing transactions (like Uber) cross multiple tax jurisdictions with different rules (GST, VAT, CESS). GeoTax solves:

- **Accuracy**: Compute taxes with financial precision (BigDecimal, HALF_EVEN rounding per India GST guidelines)
- **Multi-jurisdiction**: A single ride may span two states, each with independent tax rules
- **Resilience**: Rule providers may be slow or unavailable; the system must not cascade-fail
- **Idempotency**: Network retries must not double-charge customers
- **Auditability**: Every result records the exact rule version applied, for compliance replay

---

## Architecture Overview

```mermaid
flowchart TD
    Client(["Client"])

    subgraph Service Layer
        SVC["TaxCalculationService
        Idempotency cache (transactionId → result)
        Metrics (LongAdder)
        Batch parallelStream()"]
    end

    subgraph Engine Layer
        ENG["TaxCalculationEngine
        Identifies jurisdictions, 
        Parallel rule fetch,
        (CompletableFuture)
        Rule filtering
        Audit trail builder"]
    end

    subgraph Resilience Layer
        CB["CircuitBreaker
        CLOSED → OPEN → HALF_OPEN
        (lock-free Atomics)"]
        RB["RetryWithBackoff
        Exp. backoff + jitter (Max 3 attempts)"]
    end

    subgraph Provider Layer
        IFACE["«interface»
        JurisdictionRuleProvider"]
        INMEM["InMemoryRuleProvider
        IN-TS · IN-MH · IN-KA"]
        REDIS(["RedisRuleProvider 
        (extension point)"])
        DB(["DBRuleProvider 
        (extension point)"])
    end

    subgraph Data Models
        REQ["TaxCalculationRequest
        baseFare · rideType
        origin/destination
        jurisdictions · timestamp"]
        RULE["TaxRule (immutable)
        ruleId · version
        jurisdictionCode
        taxType · rateeffectiveFrom/To
        applicableRideType"]
        RES["TaxCalculationResult
        lineItems[], totalTaxAmount
        appliedRuleIds@version, fromCache flag"]
    end

    Client -->|"TaxCalculationRequest"| SVC
    SVC -->|"cache miss"| ENG
    SVC -->|"cache hit\n(same transactionId)"| Client

    ENG -->|"per jurisdiction"| CB
    CB -->|"CLOSED / HALF_OPEN"| RB
    RB --> IFACE
    IFACE --> INMEM
    IFACE -.->|future| REDIS
    IFACE -.->|future| DB

    ENG --- REQ
    IFACE --- RULE
    ENG --- RES

    SVC -->|"TaxCalculationResult"| Client
```

---

## Request Flow (Happy Path)

```mermaid
sequenceDiagram
    participant C as Client
    participant S as TaxCalculationService
    participant E as TaxCalculationEngine
    participant CB as CircuitBreaker
    participant P as RuleProvider

    C->>S: calculateTax(request)
    S->>S: check idempotency cache (transactionId)
    alt Cache Hit
        S-->>C: cached TaxCalculationResult (fromCache=true)
    else Cache Miss
        S->>E: calculate(request)
        par Fetch IN-TS rules
            E->>CB: call(getRules("IN-TS"))
            CB->>P: getRulesForJurisdiction("IN-TS")
            P-->>CB: List<TaxRule>
            CB-->>E: List<TaxRule>
        and Fetch IN-MH rules
            E->>CB: call(getRules("IN-MH"))
            CB->>P: getRulesForJurisdiction("IN-MH")
            P-->>CB: List<TaxRule>
            CB-->>E: List<TaxRule>
        end
        E->>E: filter rules (date range + rideType)
        E->>E: compute BigDecimal tax per rule
        E->>E: build audit trail (ruleId@version)
        E-->>S: TaxCalculationResult
        S->>S: store in idempotency cache
        S-->>C: TaxCalculationResult
    end
```

---

## Circuit Breaker State Machine

```mermaid
stateDiagram-v2
    [*] --> CLOSED
    CLOSED --> OPEN : failureCount >= 3
    OPEN --> HALF_OPEN : openDuration >= 30s
    HALF_OPEN --> CLOSED : probe succeeds
    HALF_OPEN --> OPEN : probe fails

    CLOSED : Normal operation\nTrack failures
    OPEN : Fast-fail all requests\nNo provider calls
    HALF_OPEN : Allow one probe\nTest recovery
```

---

## Key Design Decisions

| Concern | Solution | Reason |
|---|---|---|
| Financial precision | `BigDecimal` + `HALF_EVEN` | India GST mandate; avoid float rounding errors |
| Concurrency | Immutable models + lock-free `Atomic*` | Thread-safe at 50k rides/min without locks |
| Duplicate charges | Idempotency cache by `transactionId` | Network retries must not re-bill |
| Provider failures | Circuit breaker + exponential retry | Prevent cascade failure; thundering-herd protection |
| Multi-jurisdiction latency | `CompletableFuture` parallel fetch | 100ms vs 200ms sequential for 2 jurisdictions |
| Compliance | `appliedRuleIds@version` in every result | Replay any past calculation exactly |
| Testability | `Clock` injection + provider interface | Fixed-time tests; swap real provider for mocks |
