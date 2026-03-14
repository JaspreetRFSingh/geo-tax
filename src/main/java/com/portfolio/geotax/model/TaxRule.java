package com.portfolio.geotax.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable domain model representing a single tax rule for a jurisdiction.
 *
 * LEARNING NOTE — Why immutable?
 * Tax rules are shared across concurrent threads (multiple ride calculations
 * running in parallel). Making TaxRule immutable means no synchronization is
 * needed — any thread can safely read the same object. This is the "share
 * nothing mutable" principle that underpins safe concurrent Java code.
 *
 * LEARNING NOTE — Why BigDecimal for rate?
 * NEVER use double/float for financial calculations. IEEE 754 floating-point
 * arithmetic cannot represent many decimal fractions exactly.
 *   double d = 0.1 + 0.2;  // → 0.30000000000000004
 * BigDecimal gives you exact decimal arithmetic + configurable rounding modes.
 */
public final class TaxRule {

    public enum TaxType { GST, VAT, SERVICE_TAX, SURCHARGE, CESS }
    public enum RideType { STANDARD, PREMIUM, MOTO, AUTO, INTERCITY }

    private final String ruleId;
    private final String jurisdictionCode;   // e.g. "IN-TS", "IN-MH", "US-CA"
    private final TaxType taxType;
    private final BigDecimal rate;           // e.g. 0.05 for 5%
    private final RideType applicableRideType; // null means applies to all
    private final Instant effectiveFrom;
    private final Instant effectiveTo;       // null means no expiry
    private final long version;              // optimistic locking

    private TaxRule(Builder b) {
        this.ruleId             = Objects.requireNonNull(b.ruleId);
        this.jurisdictionCode   = Objects.requireNonNull(b.jurisdictionCode);
        this.taxType            = Objects.requireNonNull(b.taxType);
        this.rate               = Objects.requireNonNull(b.rate);
        this.applicableRideType = b.applicableRideType;
        this.effectiveFrom      = Objects.requireNonNull(b.effectiveFrom);
        this.effectiveTo        = b.effectiveTo;
        this.version            = b.version;
    }

    public boolean isActiveAt(Instant moment) {
        return !moment.isBefore(effectiveFrom) &&
               (effectiveTo == null || moment.isBefore(effectiveTo));
    }

    public boolean appliesTo(RideType rideType) {
        return applicableRideType == null || applicableRideType == rideType;
    }

    // --- Getters ---
    public String getRuleId()           { return ruleId; }
    public String getJurisdictionCode() { return jurisdictionCode; }
    public TaxType getTaxType()         { return taxType; }
    public BigDecimal getRate()         { return rate; }
    public RideType getApplicableRideType() { return applicableRideType; }
    public Instant getEffectiveFrom()   { return effectiveFrom; }
    public Instant getEffectiveTo()     { return effectiveTo; }
    public long getVersion()            { return version; }

    @Override
    public String toString() {
        return String.format("TaxRule[%s | %s | %s | %.4f%%]",
                ruleId, jurisdictionCode, taxType, rate.multiply(BigDecimal.valueOf(100)));
    }

    // --- Builder ---
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String ruleId, jurisdictionCode;
        private TaxType taxType;
        private BigDecimal rate;
        private RideType applicableRideType;
        private Instant effectiveFrom = Instant.EPOCH;
        private Instant effectiveTo;
        private long version = 1L;

        public Builder ruleId(String v)             { this.ruleId = v; return this; }
        public Builder jurisdictionCode(String v)   { this.jurisdictionCode = v; return this; }
        public Builder taxType(TaxType v)           { this.taxType = v; return this; }
        public Builder rate(String v)               { this.rate = new BigDecimal(v); return this; }
        public Builder rate(BigDecimal v)           { this.rate = v; return this; }
        public Builder applicableRideType(RideType v){ this.applicableRideType = v; return this; }
        public Builder effectiveFrom(Instant v)     { this.effectiveFrom = v; return this; }
        public Builder effectiveTo(Instant v)       { this.effectiveTo = v; return this; }
        public Builder version(long v)              { this.version = v; return this; }
        public TaxRule build()                      { return new TaxRule(this); }
    }
}
