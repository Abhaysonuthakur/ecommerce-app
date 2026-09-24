package com.shop.ecommerce.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * The lifecycle of an order.
 *
 * <pre>
 *   PENDING ──→ CONFIRMED ──→ PROCESSING ──→ SHIPPED ──→ DELIVERED
 *      │            │              │            │
 *      └────────────┴──────────────┴────────────┘
 *                        │
 *                        ▼
 *                   CANCELLED
 * </pre>
 *
 * <p><b>WHY the transitions are encoded in code rather than left to the caller:</b>
 * "status is just a field you set" produces orders that go from {@code DELIVERED}
 * back to {@code PENDING}, and refunds that cannot be reconciled. A shop's order
 * state is a state machine, and the legal moves belong next to the states.
 *
 * <p>Keeping the rule here - rather than in the service - means it is testable on its
 * own and cannot be bypassed by a second code path that also changes a status.
 */
public enum OrderStatus {

    /** Created, awaiting confirmation. Stock has already been reserved. */
    PENDING,

    /** Confirmed by the store. */
    CONFIRMED,

    /** Being picked and packed. */
    PROCESSING,

    /** Handed to the carrier. */
    SHIPPED,

    /** Received by the customer. Terminal. */
    DELIVERED,

    /** Cancelled. Terminal, and the only status that restores stock. */
    CANCELLED;

    /**
     * The statuses this one may move to.
     *
     * <p>{@code DELIVERED} and {@code CANCELLED} return an empty set - they are
     * terminal. That is what stops a delivered order being "un-delivered" by a
     * stray admin click, which would otherwise corrupt revenue reporting.
     *
     * <p>Cancellation is allowed from anywhere except the two terminal states,
     * including from {@code SHIPPED}. Real shops differ on the last case; allowing
     * it here keeps the rule simple, and the alternative - a returns flow - is
     * explicitly out of scope (see docs/01-REQUIREMENTS.md).
     */
    public Set<OrderStatus> allowedNext() {
        return switch (this) {
            case PENDING -> EnumSet.of(CONFIRMED, CANCELLED);
            case CONFIRMED -> EnumSet.of(PROCESSING, CANCELLED);
            case PROCESSING -> EnumSet.of(SHIPPED, CANCELLED);
            case SHIPPED -> EnumSet.of(DELIVERED, CANCELLED);
            case DELIVERED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    /**
     * Whether moving this -> {@code target} is legal.
     *
     * <p>Note that a no-op transition (same status to same status) is refused. It is
     * not a legal move; it is almost always a double-click, and accepting it would
     * write a misleading row into the audit trail.
     */
    public boolean canTransitionTo(OrderStatus target) {
        return target != null && allowedNext().contains(target);
    }

    /** A terminal status cannot change, so the UI should not offer to change it. */
    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED;
    }

    /**
     * Cancelling restores the stock that placing the order took. Every other
     * transition leaves inventory alone.
     */
    public boolean restoresStock() {
        return this == CANCELLED;
    }
}
