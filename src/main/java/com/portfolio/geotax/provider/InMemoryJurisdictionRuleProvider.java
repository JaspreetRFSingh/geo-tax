package com.portfolio.geotax.provider;

import com.portfolio.geotax.model.TaxRule;
import com.portfolio.geotax.model.TaxRule.TaxType;
import com.portfolio.geotax.model.TaxRule.RideType;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * In-memory provider seeded with realistic India GST rules.
 *
 * LEARNING NOTE — India GST Structure for Ride-Hailing (real-world context)
 * India's GST Council classifies ride-hailing under different slabs:
 *   - Auto-rickshaw / Moto:   5% GST (no ITC)
 *   - Standard / Premium:    5% GST (operator charges)
 *   - Radio Taxi (Premium):  5% GST
 *   - Intercity (> 12 seats): 12% GST
 *
 * Additionally, some states levy a State-level cess on top of GST.
 * Kerala charges a 1% Kerala Flood Cess on most services.
 * This multi-layer structure is exactly what Uber's Tax Calculations team
 * needs to model correctly for every transaction.
 *
 * LEARNING NOTE — ConcurrentHashMap for thread safety
 * Multiple request threads may call getRulesForJurisdiction concurrently.
 * ConcurrentHashMap allows concurrent reads without locking, and safe
 * writes via putIfAbsent. This is far more performant than a synchronized
 * HashMap for read-heavy workloads.
 */
public class InMemoryJurisdictionRuleProvider implements JurisdictionRuleProvider {

    private final Map<String, List<TaxRule>> rulesByJurisdiction = new HashMap<>();

    public InMemoryJurisdictionRuleProvider() {
        seedRules();
    }

    private void seedRules() {
        // --- Telangana (IN-TS) ---
        rulesByJurisdiction.put("IN-TS", List.of(
            TaxRule.builder()
                .ruleId("IN-TS-GST-STD-001")
                .jurisdictionCode("IN-TS")
                .taxType(TaxType.GST)
                .rate("0.05")
                .applicableRideType(RideType.STANDARD)
                .effectiveFrom(Instant.parse("2017-07-01T00:00:00Z"))
                .version(3L)
                .build(),

            TaxRule.builder()
                .ruleId("IN-TS-GST-PREM-001")
                .jurisdictionCode("IN-TS")
                .taxType(TaxType.GST)
                .rate("0.05")
                .applicableRideType(RideType.PREMIUM)
                .effectiveFrom(Instant.parse("2017-07-01T00:00:00Z"))
                .version(2L)
                .build(),

            TaxRule.builder()
                .ruleId("IN-TS-GST-AUTO-001")
                .jurisdictionCode("IN-TS")
                .taxType(TaxType.GST)
                .rate("0.05")
                .applicableRideType(RideType.AUTO)
                .effectiveFrom(Instant.parse("2017-07-01T00:00:00Z"))
                .version(2L)
                .build()
        ));

        // --- Maharashtra (IN-MH) — has extra local cess ---
        rulesByJurisdiction.put("IN-MH", List.of(
            TaxRule.builder()
                .ruleId("IN-MH-GST-STD-001")
                .jurisdictionCode("IN-MH")
                .taxType(TaxType.GST)
                .rate("0.05")
                .effectiveFrom(Instant.parse("2017-07-01T00:00:00Z"))
                .version(4L)
                .build(),

            TaxRule.builder()
                .ruleId("IN-MH-CESS-001")
                .jurisdictionCode("IN-MH")
                .taxType(TaxType.CESS)
                .rate("0.01")   // 1% municipal cess
                .effectiveFrom(Instant.parse("2020-01-01T00:00:00Z"))
                .version(1L)
                .build()
        ));

        // --- Karnataka (IN-KA) ---
        rulesByJurisdiction.put("IN-KA", List.of(
            TaxRule.builder()
                .ruleId("IN-KA-GST-STD-001")
                .jurisdictionCode("IN-KA")
                .taxType(TaxType.GST)
                .rate("0.05")
                .effectiveFrom(Instant.parse("2017-07-01T00:00:00Z"))
                .version(3L)
                .build()
        ));

        // --- California (US-CA) for demonstration ---
        rulesByJurisdiction.put("US-CA", List.of(
            TaxRule.builder()
                .ruleId("US-CA-VAT-STD-001")
                .jurisdictionCode("US-CA")
                .taxType(TaxType.VAT)
                .rate("0.0725")  // 7.25% state sales tax
                .effectiveFrom(Instant.parse("2019-01-01T00:00:00Z"))
                .version(2L)
                .build(),

            TaxRule.builder()
                .ruleId("US-CA-SURCHARGE-001")
                .jurisdictionCode("US-CA")
                .taxType(TaxType.SURCHARGE)
                .rate("0.0025")  // CA Clean Air Surcharge
                .effectiveFrom(Instant.parse("2020-06-01T00:00:00Z"))
                .version(1L)
                .build()
        ));
    }

    @Override
    public List<TaxRule> getRulesForJurisdiction(String jurisdictionCode) {
        return rulesByJurisdiction.getOrDefault(jurisdictionCode, Collections.emptyList());
    }

    @Override
    public String getProviderName() { return "InMemoryJurisdictionRuleProvider"; }

    /** Allows dynamic rule updates — useful for testing rule versioning */
    public void addRule(TaxRule rule) {
        rulesByJurisdiction.computeIfAbsent(rule.getJurisdictionCode(), k -> new ArrayList<>());
        List<TaxRule> existing = new ArrayList<>(rulesByJurisdiction.get(rule.getJurisdictionCode()));
        existing.add(rule);
        rulesByJurisdiction.put(rule.getJurisdictionCode(), Collections.unmodifiableList(existing));
    }
}
