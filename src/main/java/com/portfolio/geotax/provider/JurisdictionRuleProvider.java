package com.portfolio.geotax.provider;

import com.portfolio.geotax.model.TaxRule;

import java.util.List;

/**
 * Contract for fetching tax rules for a given jurisdiction.
 *
 * LEARNING NOTE — Programming to an Interface (Dependency Inversion Principle)
 * The TaxCalculationEngine depends on THIS interface, not on a concrete class.
 * This means you can swap implementations:
 *   - InMemoryJurisdictionRuleProvider  → unit tests / local dev
 *   - RedisJurisdictionRuleProvider     → staging/production with caching
 *   - DatabaseJurisdictionRuleProvider  → primary source of truth
 *   - RemoteJurisdictionRuleProvider    → if rules live in a separate microservice
 *
 * This is the "Open/Closed Principle" in action: the engine is open for
 * extension (new provider types) but closed for modification.
 *
 * LEARNING NOTE — Why throws Exception on the interface?
 * In distributed systems, fetching rules from Redis/DB/remote service CAN fail.
 * The interface acknowledges this reality. The caller (TaxCalculationEngine)
 * can then apply fault-tolerance strategies (circuit breaker, retry, fallback).
 */
public interface JurisdictionRuleProvider {
    List<TaxRule> getRulesForJurisdiction(String jurisdictionCode) throws Exception;
    String getProviderName();
}
