package com.paypulse.domain.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum PaymentStatus {
    CREATED(10, false),
    AUTHENTICATION_REQUIRED(15, false),
    AUTHORIZING(20, false),
    AUTHORIZED(30, false),
    CAPTURING(40, false),
    SUCCEEDED(50, true),
    DECLINED(50, true),
    CANCELLED(50, true),
    REFUNDING(55, false),
    PARTIALLY_REFUNDED(58, false),
    REFUNDED(60, true),
    REQUIRES_RECONCILIATION(25, false),
    FAILED(50, true);

    private final int sequenceNumber;
    private final boolean terminal;

    PaymentStatus(int sequenceNumber, boolean terminal) {
        this.sequenceNumber = sequenceNumber;
        this.terminal = terminal;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public boolean isTerminal() {
        return terminal;
    }

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS;

    static {
        ALLOWED_TRANSITIONS = Map.ofEntries(
            Map.entry(CREATED, EnumSet.of(AUTHORIZING, FAILED)),
            Map.entry(AUTHENTICATION_REQUIRED, EnumSet.of(AUTHORIZING, DECLINED, FAILED)),
            Map.entry(AUTHORIZING, EnumSet.of(
                AUTHENTICATION_REQUIRED, AUTHORIZED, SUCCEEDED, DECLINED, REQUIRES_RECONCILIATION, FAILED
            )),
            Map.entry(AUTHORIZED, EnumSet.of(
                CAPTURING, CANCELLED, REQUIRES_RECONCILIATION, FAILED
            )),
            Map.entry(CAPTURING, EnumSet.of(
                SUCCEEDED, REQUIRES_RECONCILIATION, FAILED
            )),
            Map.entry(SUCCEEDED, EnumSet.of(
                REFUNDING
            )),
            Map.entry(REFUNDING, EnumSet.of(
                REFUNDED, PARTIALLY_REFUNDED, SUCCEEDED, REQUIRES_RECONCILIATION, FAILED
            )),
            Map.entry(PARTIALLY_REFUNDED, EnumSet.of(
                REFUNDING
            )),
            Map.entry(REQUIRES_RECONCILIATION, EnumSet.of(
                AUTHORIZED, SUCCEEDED, DECLINED, CANCELLED, REFUNDED, FAILED
            )),
            Map.entry(DECLINED, Collections.emptySet()),
            Map.entry(CANCELLED, Collections.emptySet()),
            Map.entry(REFUNDED, Collections.emptySet()),
            Map.entry(FAILED, Collections.emptySet())
        );
    }

    public boolean canTransitionTo(PaymentStatus target) {
        if (target == null) return false;
        if (this == target) return true; // Idempotent self-transition
        Set<PaymentStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(this, Collections.emptySet());
        return allowed.contains(target);
    }

    public boolean isSupersededBy(PaymentStatus incoming) {
        if (incoming == null) return false;
        // If current state sequence is greater than or equal to incoming state, incoming is superseded
        // e.g. SUCCEEDED (50) cannot be regressed by an older AUTHORIZING (20) webhook
        return this.sequenceNumber >= incoming.sequenceNumber && this.isTerminal();
    }
}
