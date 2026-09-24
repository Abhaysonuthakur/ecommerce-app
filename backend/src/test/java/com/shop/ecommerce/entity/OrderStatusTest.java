package com.shop.ecommerce.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order state machine, tested as a table rather than as prose.
 *
 * <h2>Why this test is worth more than it looks</h2>
 *
 * <p>Every rule here was verified by hand against the running application, and the
 * verification is already gone - it lived in a shell history. A test makes it permanent.
 * The specific value is that {@link OrderStatus#canTransitionTo} is the one guard standing
 * between an admin's mis-click and corrupted revenue reporting, and the failure it prevents
 * is silent: a {@code DELIVERED -> PENDING} move does not throw, it just makes last month's
 * numbers wrong.
 *
 * <p>The whole legal-move table is asserted at once. A test that checked only
 * "PENDING can move to CONFIRMED" would still pass if someone added a
 * {@code DELIVERED -> PENDING} case, because nothing would be asserting the <em>absence</em>.
 * Changing the table deliberately means editing this test, which is the point.
 */
@DisplayName("OrderStatus - the order lifecycle state machine")
class OrderStatusTest {

    /**
     * The complete set of legal moves, written out longhand.
     *
     * <p>Deliberately a full enumeration instead of a loop over {@code allowedNext()},
     * because a test that derives its expectations from the code under test proves only
     * that the code is self-consistent. If someone swapped {@code CONFIRMED} and
     * {@code PROCESSING} everywhere, a self-referential test would still pass.
     */
    @Test
    @DisplayName("the legal-move table is exactly what the business requires")
    void legalMovesAreExactlyAsSpecified() {
        assertThat(OrderStatus.PENDING.allowedNext())
                .containsExactlyInAnyOrder(OrderStatus.CONFIRMED, OrderStatus.CANCELLED);

        assertThat(OrderStatus.CONFIRMED.allowedNext())
                .containsExactlyInAnyOrder(OrderStatus.PROCESSING, OrderStatus.CANCELLED);

        assertThat(OrderStatus.PROCESSING.allowedNext())
                .containsExactlyInAnyOrder(OrderStatus.SHIPPED, OrderStatus.CANCELLED);

        assertThat(OrderStatus.SHIPPED.allowedNext())
                .containsExactlyInAnyOrder(OrderStatus.DELIVERED, OrderStatus.CANCELLED);
    }

    @Nested
    @DisplayName("terminal statuses")
    class Terminal {

        @ParameterizedTest(name = "{0} has no legal next status")
        @EnumSource(value = OrderStatus.class, names = {"DELIVERED", "CANCELLED"})
        void terminalStatusesAllowNothing(OrderStatus terminal) {
            assertThat(terminal.allowedNext()).isEmpty();
            assertThat(terminal.isTerminal()).isTrue();
        }

        /**
         * The case the specification calls out: a delivered order cannot be "un-delivered".
         *
         * <p>Asserted against <em>every</em> other status, not just the obvious one, because
         * the failure mode is a single permissive line somewhere in a switch.
         */
        @ParameterizedTest(name = "{0} cannot move to DELIVERED or CANCELLED")
        @EnumSource(value = OrderStatus.class, names = {"DELIVERED", "CANCELLED"})
        void terminalStatusesCannotMoveAnywhere(OrderStatus terminal) {
            for (OrderStatus any : OrderStatus.values()) {
                assertThat(terminal.canTransitionTo(any))
                        .as("%s -> %s must be refused", terminal, any)
                        .isFalse();
            }
        }

        @ParameterizedTest(name = "{0} is not terminal")
        @EnumSource(value = OrderStatus.class, names = {"PENDING", "CONFIRMED", "PROCESSING", "SHIPPED"})
        void inFlightStatusesAreNotTerminal(OrderStatus inFlight) {
            assertThat(inFlight.isTerminal()).isFalse();
        }
    }

    @Nested
    @DisplayName("no-op transitions")
    class NoOp {

        /**
         * A status moving to itself is refused, and the reason is not pedantry.
         *
         * <p>It is almost always a double-clicked button in the admin UI. Accepting it would
         * write a "status changed from CONFIRMED to CONFIRMED" row into the audit trail -
         * which is indistinguishable from a real event and makes a support investigation
         * chase a change that never happened.
         */
        @ParameterizedTest(name = "{0} -> {0} is refused")
        @EnumSource(OrderStatus.class)
        void selfTransitionIsNeverLegal(OrderStatus status) {
            assertThat(status.canTransitionTo(status))
                    .as("a no-op transition must be refused so the audit trail stays honest")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a null target is refused rather than throwing NPE")
    void nullTargetIsRefused() {
        /*
         * Null-safety matters here because the target can arrive from a request body. The
         * alternative - letting contains() throw NPE - would surface as a 500 instead of a
         * 400, turning a client's missing field into "the server broke".
         */
        assertThat(OrderStatus.PENDING.canTransitionTo(null)).isFalse();
    }

    @Test
    @DisplayName("cancelled is the only status that restores stock")
    void onlyCancelledRestoresStock() {
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(status.restoresStock())
                    .as("restoresStock() for %s", status)
                    .isEqualTo(status == OrderStatus.CANCELLED);
        }
    }

    @Test
    @DisplayName("every non-terminal status can reach CANCELLED")
    void cancellationIsReachableFromEveryLiveStatus() {
        /*
         * The business rule stated in the enum's javadoc: cancellation is allowed from
         * anywhere that is not already final. Stated as a property rather than four
         * individual assertions, so a new in-flight status added later cannot be forgotten.
         */
        for (OrderStatus status : OrderStatus.values()) {
            if (!status.isTerminal()) {
                assertThat(status.canTransitionTo(OrderStatus.CANCELLED))
                        .as("%s must be cancellable", status)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("allowedNext never reports a status the enum does not define")
    void allowedNextNeverLeaksForeignConstants() {
        /*
         * EnumSet is typed, so this looks impossible - and it is, which is exactly why the
         * assertion is cheap. It exists to fail loudly if someone replaces EnumSet.of with
         * a raw Set that picks up a constant from a different enum.
         */
        for (OrderStatus status : OrderStatus.values()) {
            Set<OrderStatus> next = status.allowedNext();
            assertThat(next).isSubsetOf(EnumSet.allOfStatuses());
        }
    }

    /** Tiny helper so the assertion above reads as intent rather than as plumbing. */
    private static final class EnumSet {
        static Set<OrderStatus> allOfStatuses() {
            return Set.of(OrderStatus.values());
        }
    }
}
