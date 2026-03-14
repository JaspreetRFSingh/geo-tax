package com.portfolio.geotax.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents an incoming request to calculate taxes for a ride transaction.
 *
 * LEARNING NOTE — Why separate Request/Result from domain model (TaxRule)?
 * This is the DTO (Data Transfer Object) pattern. It keeps your API contract
 * decoupled from your internal domain. If Uber changes the internal tax rule
 * structure, the external request format doesn't need to change, and vice versa.
 * This is especially important in a microservices architecture where the Tax
 * service is called by many upstream services (Rides, Eats, Freight).
 */
public final class TaxCalculationRequest {

    private final String transactionId;
    private final String rideId;
    private final BigDecimal baseFare;           // pre-tax amount
    private final String originJurisdiction;     // e.g. "IN-TS" (Telangana)
    private final String destinationJurisdiction;// for inter-state rides
    private final TaxRule.RideType rideType;
    private final Instant rideCompletedAt;

    private TaxCalculationRequest(Builder b) {
        this.transactionId          = b.transactionId != null ? b.transactionId : UUID.randomUUID().toString();
        this.rideId                 = Objects.requireNonNull(b.rideId);
        this.baseFare               = Objects.requireNonNull(b.baseFare);
        this.originJurisdiction     = Objects.requireNonNull(b.originJurisdiction);
        this.destinationJurisdiction= b.destinationJurisdiction;
        this.rideType               = b.rideType != null ? b.rideType : TaxRule.RideType.STANDARD;
        this.rideCompletedAt        = b.rideCompletedAt != null ? b.rideCompletedAt : Instant.now();
    }

    public String getTransactionId()            { return transactionId; }
    public String getRideId()                   { return rideId; }
    public BigDecimal getBaseFare()             { return baseFare; }
    public String getOriginJurisdiction()       { return originJurisdiction; }
    public String getDestinationJurisdiction()  { return destinationJurisdiction; }
    public TaxRule.RideType getRideType()       { return rideType; }
    public Instant getRideCompletedAt()         { return rideCompletedAt; }

    public boolean isInterJurisdiction() {
        return destinationJurisdiction != null &&
               !destinationJurisdiction.equals(originJurisdiction);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String transactionId, rideId, originJurisdiction, destinationJurisdiction;
        private BigDecimal baseFare;
        private TaxRule.RideType rideType;
        private Instant rideCompletedAt;

        public Builder transactionId(String v)          { this.transactionId = v; return this; }
        public Builder rideId(String v)                 { this.rideId = v; return this; }
        public Builder baseFare(String v)               { this.baseFare = new BigDecimal(v); return this; }
        public Builder baseFare(BigDecimal v)           { this.baseFare = v; return this; }
        public Builder originJurisdiction(String v)     { this.originJurisdiction = v; return this; }
        public Builder destinationJurisdiction(String v){ this.destinationJurisdiction = v; return this; }
        public Builder rideType(TaxRule.RideType v)     { this.rideType = v; return this; }
        public Builder rideCompletedAt(Instant v)       { this.rideCompletedAt = v; return this; }
        public TaxCalculationRequest build()            { return new TaxCalculationRequest(this); }
    }
}
