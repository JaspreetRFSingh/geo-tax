package com.portfolio.geotax.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Immutable result of a tax calculation.
 *
 * LEARNING NOTE — Audit Trail in FinTech
 * The 'appliedRuleIds' field is critically important for FinTech systems.
 * Every tax calculation must be reproducible and auditable. Uber needs to
 * answer questions like "why was this rider charged X tax in March 2024?"
 * months later. Storing which exact rule versions were applied (with their
 * version numbers) makes the calculation fully reproducible and defensible
 * to tax authorities.
 */
public final class TaxCalculationResult {

    public record TaxLineItem(
        String ruleId,
        TaxRule.TaxType taxType,
        String jurisdictionCode,
        BigDecimal taxableAmount,
        BigDecimal rate,
        BigDecimal taxAmount      // taxableAmount * rate, rounded
    ) {}

    private final String transactionId;
    private final BigDecimal baseFare;
    private final List<TaxLineItem> lineItems;
    private final BigDecimal totalTaxAmount;
    private final BigDecimal totalAmountWithTax;
    private final List<String> appliedRuleIds;  // for audit trail
    private final Instant computedAt;
    private final boolean fromCache;

    public TaxCalculationResult(
            String transactionId,
            BigDecimal baseFare,
            List<TaxLineItem> lineItems,
            List<String> appliedRuleIds,
            boolean fromCache) {

        this.transactionId    = transactionId;
        this.baseFare         = baseFare;
        this.lineItems        = Collections.unmodifiableList(lineItems);
        this.appliedRuleIds   = Collections.unmodifiableList(appliedRuleIds);
        this.fromCache        = fromCache;
        this.computedAt       = Instant.now();

        // LEARNING NOTE — RoundingMode.HALF_EVEN (Banker's Rounding)
        // India's GST uses HALF_EVEN rounding. It rounds .5 to the nearest
        // even number, which statistically reduces accumulated rounding errors
        // across millions of transactions. This is NOT the same as HALF_UP
        // which most people expect. Getting this wrong = regulatory issues.
        this.totalTaxAmount = lineItems.stream()
                .map(TaxLineItem::taxAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_EVEN);

        this.totalAmountWithTax = baseFare.add(totalTaxAmount)
                .setScale(2, RoundingMode.HALF_EVEN);
    }

    public String getTransactionId()            { return transactionId; }
    public BigDecimal getBaseFare()             { return baseFare; }
    public List<TaxLineItem> getLineItems()     { return lineItems; }
    public BigDecimal getTotalTaxAmount()       { return totalTaxAmount; }
    public BigDecimal getTotalAmountWithTax()   { return totalAmountWithTax; }
    public List<String> getAppliedRuleIds()     { return appliedRuleIds; }
    public Instant getComputedAt()              { return computedAt; }
    public boolean isFromCache()                { return fromCache; }

    @Override
    public String toString() {
        return String.format(
            "TaxCalculationResult[txn=%s | base=%.2f | tax=%.2f | total=%.2f | rules=%s]",
            transactionId, baseFare, totalTaxAmount, totalAmountWithTax, appliedRuleIds);
    }
}
