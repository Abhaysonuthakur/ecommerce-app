package com.shop.ecommerce.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Entity invariants: the rules that must hold no matter which service calls them.
 *
 * <h2>Why these rules live on the entity, and why they are tested here</h2>
 *
 * <p>Stock, money and the price snapshot are the three things in this schema that are
 * expensive to get wrong and cheap to get right. Putting their rules on the entity means
 * there is exactly one implementation of each - the cart, the order, and the cancellation
 * path all call the same {@code hasStockFor}, so they cannot disagree.
 *
 * <p>That is only true if the rules are actually enforced. These tests are what make the
 * claim checkable: a future refactor that "simplifies" {@code reduceStock} into a plain
 * setter fails here, rather than in production when an order ships with negative stock.
 */
@DisplayName("Entity invariants")
class EntityInvariantsTest {

    /** A product with sane defaults; individual tests override only what they care about. */
    private static Product product(String name, String price, int stock, boolean active) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(new BigDecimal(price));
        product.setStock(stock);
        product.setActive(active);
        return product;
    }

    // =================================================================
    //  Product - stock
    // =================================================================

    @Nested
    @DisplayName("Product stock")
    class Stock {

        @Test
        @DisplayName("hasStockFor is true when the stock exactly equals the request")
        void exactStockIsAvailable() {
            /*
             * The boundary, and the one that matters: "do I have 3?" when I have exactly 3
             * is yes. An off-by-one here shows up as a product that can never be the last
             * one sold - a bug nobody reports and everybody works around.
             */
            assertThat(product("Thing", "10.00", 3, true).hasStockFor(3)).isTrue();
        }

        @Test
        @DisplayName("hasStockFor is false one unit over")
        void oneOverIsNotAvailable() {
            assertThat(product("Thing", "10.00", 3, true).hasStockFor(4)).isFalse();
        }

        @Test
        @DisplayName("a zero-stock product is never available, even for one unit")
        void zeroStockIsUnavailable() {
            assertThat(product("Thing", "10.00", 0, true).hasStockFor(1)).isFalse();
        }

        @Test
        @DisplayName("an inactive product is unavailable however much stock it has")
        void inactiveIsUnavailableRegardlessOfStock() {
            /*
             * The interaction that a single-field check would miss. "Withdrawn from sale"
             * must beat "999 in the warehouse" - otherwise a delisted product stays
             * purchasable to anyone holding a stale link.
             */
            assertThat(product("Thing", "10.00", 999, false).hasStockFor(1)).isFalse();
        }

        @Test
        @DisplayName("a null stock does not throw, it reports unavailable")
        void nullStockIsUnavailable() {
            Product product = product("Thing", "10.00", 0, true);
            product.setStock(null);
            assertThat(product.hasStockFor(1)).isFalse();
        }

        @Test
        @DisplayName("reduceStock subtracts the requested amount")
        void reduceStockSubtracts() {
            Product product = product("Thing", "10.00", 10, true);
            product.reduceStock(4);
            assertThat(product.getStock()).isEqualTo(6);
        }

        @Test
        @DisplayName("reduceStock to exactly zero is allowed")
        void reduceStockToZeroIsAllowed() {
            Product product = product("Thing", "10.00", 10, true);
            product.reduceStock(10);
            assertThat(product.getStock()).isZero();
        }

        @Test
        @DisplayName("reduceStock beyond the available amount throws rather than clamping")
        void reduceStockBeyondStockThrows() {
            /*
             * Throwing, not clamping, is the deliberate choice documented on the method: a
             * silent clamp places the order AND leaves the inventory wrong, and nobody finds
             * out until a customer is told their item cannot ship. Throwing rolls the
             * transaction back and the customer gets an honest error.
             *
             * Note the exception type: IllegalStateException, not IllegalArgumentException.
             * The argument (4) is perfectly legal; the *state* is what makes the call invalid.
             */
            Product product = product("Thing", "10.00", 3, true);

            assertThatThrownBy(() -> product.reduceStock(4))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Insufficient stock")
                    .hasMessageContaining("requested 4")
                    .hasMessageContaining("available 3");

            assertThat(product.getStock())
                    .as("a failed reduction must not have partially applied")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("reduceStock on a null stock throws instead of writing a negative")
        void reduceStockWithNullStockThrows() {
            Product product = product("Thing", "10.00", 0, true);
            product.setStock(null);

            assertThatThrownBy(() -> product.reduceStock(1)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("restoreStock adds back what a cancelled order took")
        void restoreStockAddsBack() {
            Product product = product("Thing", "10.00", 3, true);

            product.reduceStock(2);
            assertThat(product.getStock()).isEqualTo(1);

            product.restoreStock(2);
            assertThat(product.getStock())
                    .as("cancelling must return inventory to exactly where it started")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("a non-positive quantity is refused by both stock operations")
        void nonPositiveQuantityIsRefused() {
            /*
             * Zero and negative quantities are how a client turns "buy" into "restock": a
             * negative reduceStock would *add* inventory and a negative restoreStock would
             * remove it. Both are refused at the entity so no service can forget.
             */
            Product product = product("Thing", "10.00", 10, true);

            assertThatThrownBy(() -> product.reduceStock(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> product.reduceStock(-5)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> product.restoreStock(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> product.restoreStock(-5)).isInstanceOf(IllegalArgumentException.class);

            assertThat(product.getStock())
                    .as("no refused call may have changed the stock")
                    .isEqualTo(10);
        }
    }

    // =================================================================
    //  Product - money
    // =================================================================

    @Nested
    @DisplayName("Product money")
    class Money {

        @Test
        @DisplayName("effectivePrice returns the price for a normal product")
        void effectivePriceReturnsThePrice() {
            assertThat(product("Thing", "149.99", 1, true).effectivePrice())
                    .isEqualByComparingTo("149.99");
        }

        @Test
        @DisplayName("effectivePrice is zero, not null, when the price is unset")
        void effectivePriceIsNullSafe() {
            /*
             * Null-safety here is what keeps a missing price from becoming an NPE two call
             * frames later, in the middle of an arithmetic expression whose stack trace
             * points at the mapper rather than at the real cause.
             */
            Product product = product("Thing", "1.00", 1, true);
            product.setPrice(null);

            assertThat(product.effectivePrice()).isNotNull().isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("compareTo, not equals, is the safe way to compare money")
        void moneyMustBeComparedWithCompareTo() {
            /*
             * The trap this documents, because it bites everyone once:
             *
             *   new BigDecimal("10.0").equals(new BigDecimal("10.00"))  -> false
             *
             * BigDecimal.equals compares scale as well as value, so a value read back from
             * DECIMAL(19,2) as 10.00 does not equal a literal 10.0 - and an assertion (or a
             * business check) written with equals fails on a value that is numerically fine.
             * compareTo ignores scale. Money comparisons must use it.
             *
             * This test asserts both halves so the rule is stated rather than implied.
             */
            BigDecimal fromDatabase = new BigDecimal("10.00");
            BigDecimal fromLiteral = new BigDecimal("10.0");

            assertThat(fromDatabase).isNotEqualTo(fromLiteral);          // scale differs
            assertThat(fromDatabase.compareTo(fromLiteral)).isZero();    // value is identical
            assertThat(fromDatabase).isEqualByComparingTo(fromLiteral);  // AssertJ's compareTo
        }
    }

    // =================================================================
    //  OrderItem - the price snapshot
    // =================================================================

    @Nested
    @DisplayName("OrderItem price snapshot")
    class PriceSnapshot {

        @Test
        @DisplayName("createSnapshot freezes the product's name, price and computed subtotal")
        void snapshotFreezesEverythingNeededForAReceipt() {
            Product product = product("Linen Shirt", "149.00", 10, true);

            OrderItem item = OrderItem.createSnapshot(product, 3);

            assertThat(item.getProductName()).isEqualTo("Linen Shirt");
            assertThat(item.getUnitPrice()).isEqualByComparingTo("149.00");
            assertThat(item.getQuantity()).isEqualTo(3);
            assertThat(item.getSubtotal()).isEqualByComparingTo("447.00");
        }

        @Test
        @DisplayName("renaming the product afterwards does not change the receipt")
        void aLaterRenameDoesNotRewriteHistory() {
            /*
             * The whole point of snapshotting. A product renamed to "Linen Shirt
             * (Discontinued)" must not change what last month's invoice says - and the
             * customer's copy of that invoice is not something the shop can go back and edit.
             */
            Product product = product("Linen Shirt", "149.00", 10, true);
            OrderItem item = OrderItem.createSnapshot(product, 1);

            product.setName("Linen Shirt (Discontinued)");

            assertThat(item.getProductName())
                    .as("the receipt must keep the name as it was at purchase time")
                    .isEqualTo("Linen Shirt");
        }

        @Test
        @DisplayName("changing the product's price afterwards does not change the order total")
        void aLaterPriceChangeDoesNotChangeTheOrder() {
            /*
             * The single most important field in the schema, per OrderItem's own javadoc.
             * If this test ever fails, every historic order total is being silently
             * recalculated against today's prices - which is not a bug you find, it is a
             * bug your accountant finds.
             */
            Product tv = product("Television", "50000.00", 5, true);
            OrderItem item = OrderItem.createSnapshot(tv, 1);

            assertThat(item.getUnitPrice()).isEqualByComparingTo("50000.00");

            tv.setPrice(new BigDecimal("42000.00"));   // a sale starts

            assertThat(item.getUnitPrice())
                    .as("the snapshot must not follow the live price")
                    .isEqualByComparingTo("50000.00");
            assertThat(item.getSubtotal()).isEqualByComparingTo("50000.00");
        }

        @Test
        @DisplayName("createSnapshot refuses a non-positive quantity")
        void snapshotRefusesNonPositiveQuantity() {
            assertThatThrownBy(() -> OrderItem.createSnapshot(product("Thing", "1.00", 5, true), 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> OrderItem.createSnapshot(product("Thing", "1.00", 5, true), -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the subtotal is recomputed whenever the quantity changes")
        void subtotalIsKeptInStepWithQuantity() {
            /*
             * The recomputation lives inside setQuantity so the two fields cannot drift -
             * there is no path that changes one without the other. This test is what makes
             * that claim checkable; the alternative is a `subtotal` column that slowly
             * disagrees with `unit_price * quantity` and a report nobody trusts.
             */
            OrderItem item = OrderItem.createSnapshot(product("Thing", "149.00", 100, true), 1);
            assertThat(item.getSubtotal()).isEqualByComparingTo("149.00");

            item.setQuantity(3);
            assertThat(item.getSubtotal()).isEqualByComparingTo("447.00");

            item.setQuantity(10);
            assertThat(item.getSubtotal()).isEqualByComparingTo("1490.00");
        }

        @Test
        @DisplayName("a fractional unit price is rounded HALF_UP to the column's scale of 2")
        void subtotalRoundsHalfUpToTwoPlaces() {
            /*
             * The column is DECIMAL(19,2) and MySQL would round silently. Rounding explicitly
             * with HALF_UP - the rounding a shop uses - means the number the customer is
             * charged is the number this code computed, not a value the database chose.
             *
             * 3 x 33.335 = 100.005 -> 100.01 (not 100.00, which banker's rounding would give)
             */
            OrderItem item = OrderItem.createSnapshot(product("Odd", "33.335", 10, true), 3);

            assertThat(item.getSubtotal().scale()).isEqualTo(2);
            assertThat(item.getSubtotal()).isEqualByComparingTo("100.01");
        }

        @Test
        @DisplayName("setQuantity refuses zero and negatives, leaving the row untouched")
        void setQuantityRefusesNonPositive() {
            OrderItem item = OrderItem.createSnapshot(product("Thing", "10.00", 10, true), 2);

            assertThatThrownBy(() -> item.setQuantity(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> item.setQuantity(-1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> item.setQuantity(null)).isInstanceOf(IllegalArgumentException.class);

            assertThat(item.getQuantity()).isEqualTo(2);
            assertThat(item.getSubtotal()).isEqualByComparingTo("20.00");
        }
    }

    // =================================================================
    //  CartItem - the live-price line
    // =================================================================

    @Nested
    @DisplayName("CartItem")
    class CartLine {

        @Test
        @DisplayName("subtotal follows the live price, because a cart shows today's price")
        void cartSubtotalUsesTheLivePrice() {
            /*
             * Deliberately the opposite of OrderItem. A cart is a quote, not a contract: if
             * the price drops while the item sits in the basket, the customer should see the
             * new price. The snapshot begins at checkout, and having it on both would create
             * two sources of truth for the same number.
             */
            Product product = product("Linen Shirt", "149.00", 10, true);
            CartItem item = new CartItem();
            item.setProduct(product);
            item.setQuantity(3);

            assertThat(item.getSubtotal()).isEqualByComparingTo("447.00");

            product.setPrice(new BigDecimal("99.00"));

            assertThat(item.getSubtotal())
                    .as("the cart line must re-price itself")
                    .isEqualByComparingTo("297.00");
        }

        @Test
        @DisplayName("setQuantity refuses zero and negatives rather than storing them")
        void setQuantityRefusesNonPositive() {
            /*
             * Guarded at the entity because the database's CHECK (quantity > 0) fires at
             * flush time - far too late to tell the customer which line was wrong. A
             * quantity of 0 in a request body means "remove this line", and the service
             * translates it into a delete rather than letting it reach the entity.
             */
            CartItem item = new CartItem();
            item.setProduct(product("Thing", "10.00", 10, true));

            assertThatThrownBy(() -> item.setQuantity(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> item.setQuantity(-3)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> item.setQuantity(null)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a line is fulfilable only while the product has the stock and is on sale")
        void fulfilabilityCombinesStockAndActive() {
            Product product = product("Thing", "10.00", 5, true);
            CartItem item = new CartItem();
            item.setProduct(product);
            item.setQuantity(5);

            assertThat(item.isFulfillable()).isTrue();

            item.setQuantity(6);
            assertThat(item.isFulfillable())
                    .as("asking for more than exists is not fulfilable")
                    .isFalse();

            item.setQuantity(1);
            product.setActive(false);
            assertThat(item.isFulfillable())
                    .as("a withdrawn product is not fulfilable however much stock remains")
                    .isFalse();
        }

        @Test
        @DisplayName("a line with no product reports zero rather than throwing")
        void nullProductIsHandled() {
            CartItem item = new CartItem();

            assertThat(item.getSubtotal()).isEqualByComparingTo("0");
            assertThat(item.isFulfillable()).isFalse();
            assertThat(item.getAvailableStock()).isZero();
        }
    }
}
