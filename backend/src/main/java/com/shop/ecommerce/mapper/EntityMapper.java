package com.shop.ecommerce.mapper;

import com.shop.ecommerce.dto.cart.CartItemResponse;
import com.shop.ecommerce.dto.cart.CartResponse;
import com.shop.ecommerce.dto.category.CategoryResponse;
import com.shop.ecommerce.dto.category.CategorySummaryResponse;
import com.shop.ecommerce.dto.order.OrderItemResponse;
import com.shop.ecommerce.dto.order.OrderResponse;
import com.shop.ecommerce.dto.product.ProductResponse;
import com.shop.ecommerce.dto.user.UserResponse;
import com.shop.ecommerce.entity.Cart;
import com.shop.ecommerce.entity.CartItem;
import com.shop.ecommerce.entity.Category;
import com.shop.ecommerce.entity.Order;
import com.shop.ecommerce.entity.OrderItem;
import com.shop.ecommerce.entity.Product;
import com.shop.ecommerce.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

import java.math.BigDecimal;
import java.util.List;

/**
 * Entity to DTO conversion for every response type.
 *
 * <h2>Why MapStruct rather than hand-written mappers</h2>
 *
 * <p>The generated implementation is plain Java - no reflection at runtime, so mapping
 * costs the same as code you typed yourself. What you buy is the compiler's attention: with
 * {@code unmappedTargetPolicy = ERROR}, adding a field to a response DTO without wiring it
 * to a source <b>fails the build</b>. A hand-written mapper would silently leave it null,
 * and the first sign of trouble would be an empty column in the UI weeks later.
 *
 * <h2>The failure mode you must guard against</h2>
 *
 * <p>MapStruct reads the entity's getters to generate this code. If Lombok has not run yet,
 * MapStruct sees an entity with no accessors and generates methods whose bodies do
 * <b>nothing</b> - it compiles, it injects, and every response is full of nulls. No warning
 * is produced anywhere.
 *
 * <p>That is why {@code pom.xml} lists Lombok in {@code annotationProcessorPaths}
 * <em>before</em> MapStruct, and includes {@code lombok-mapstruct-binding}. It is also why
 * {@code MapperOutputTest} asserts that every component of a mapped response is non-null
 * for a fully populated entity - the only way to catch a silently empty mapper is to look
 * at its output.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface EntityMapper {

    // =================================================================
    //  User
    // =================================================================

    /**
     * {@code User -> UserResponse}.
     *
     * <p><b>Note what is not mapped: {@code password}.</b> MapStruct would map it happily if
     * the target had such a component - the protection is that
     * {@link UserResponse} has no such field, so there is nothing to map onto. That is the
     * structural reason a password cannot leak from a response, as opposed to a
     * {@code @JsonIgnore} someone could remove.
     *
     * <p>No explicit {@code @Mapping} annotations are needed because every target component
     * matches a source property by name. That is worth noting: the absence of annotations
     * here is the signal that the DTO and the entity agree, and the build fails if they
     * stop agreeing.
     */
    UserResponse toUserResponse(User user);

    List<UserResponse> toUserResponseList(List<User> users);

    // =================================================================
    //  Category
    // =================================================================

    /*
     * `productCount` is deliberately ignored. It is an aggregate that requires a COUNT
     * query, which a mapper must never issue - a mapper that queries the database cannot
     * be tested without one, and it turns a pure function into an I/O operation.
     *
     * The service supplies it: it fetches all counts in a single grouped query and sets
     * the value, so the DTO is built from two sources without the mapper knowing about
     * either. Without `ignore = true` this would not compile, because
     * `unmappedTargetPolicy = ERROR` treats an unsourced (and unmapped) target as a
     * mistake. That is exactly the behaviour we want.
     */
    @Mapping(target = "productCount", ignore = true)
    CategoryResponse toCategoryResponse(Category category);

    /** The compact reference embedded in product responses. Only three fields map. */
    CategorySummaryResponse toCategorySummaryResponse(Category category);

    List<CategorySummaryResponse> toCategorySummaryResponseList(List<Category> categories);

    // =================================================================
    //  Product
    // =================================================================

    /*
     * The nested category is mapped automatically because the source property name matches
     * the target, and a mapper method for that exact pair exists above.
     * `toCategorySummaryResponse(Category) -> CategorySummaryResponse` is the method
     * MapStruct selects for the nested object - no annotation needed.
     */
    ProductResponse toProductResponse(Product product);

    List<ProductResponse> toProductResponseList(List<Product> products);

    // =================================================================
    //  Cart
    // =================================================================

    /*
     * Several computed values are ignored and set by the service or by a default method
     * below. `subtotal`, `totalItems`, `itemCount`, `empty` and `checkoutReady` are all
     * derived from the item list, and mapping them by name would either fail (no matching
     * source property) or produce the wrong answer.
     */
    @Mapping(target = "subtotal", expression = "java(cart.getSubtotal())")
    @Mapping(target = "totalItems", expression = "java(cart.getTotalQuantity())")
    @Mapping(target = "itemCount", expression = "java(cart.getItemCount())")
    @Mapping(target = "empty", expression = "java(cart.isEmpty())")
    @Mapping(target = "checkoutReady", expression = "java(isCheckoutReady(cart))")
    @Mapping(target = "items", source = "items")
    CartResponse toCartResponse(Cart cart);

    /*
     * `subtotal` is computed from the live product price rather than read from a column,
     * because a cart deliberately tracks the current price (the order is where prices
     * freeze). Calling CartItem.getSubtotal() keeps one implementation of that arithmetic.
     */
    @Mapping(target = "productId", source = "product.id")
    @Mapping(target = "productName", source = "product.name")
    @Mapping(target = "imageUrl", source = "product.imageUrl")
    @Mapping(target = "unitPrice", source = "product.price")
    @Mapping(target = "subtotal", expression = "java(item.getSubtotal())")
    @Mapping(target = "availableStock", expression = "java(item.getAvailableStock())")
    @Mapping(target = "available", expression = "java(item.getProduct() != null && item.getProduct().isActive())")
    @Mapping(target = "hasEnoughStock", expression = "java(item.isFulfillable())")
    CartItemResponse toCartItemResponse(CartItem item);

    List<CartItemResponse> toCartItemResponseList(List<CartItem> items);

    // =================================================================
    //  Order
    // =================================================================

    /*
     * The customer fields come from the order's user. Note that this works because
     * `Order.user` is LAZY and the mapper runs inside the service's transaction - outside
     * it, with `open-in-view: false`, touching it would throw LazyInitializationException.
     * That is a feature: it surfaces the problem in development instead of hiding an N+1.
     */
    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "customerName", source = "user.name")
    @Mapping(target = "customerEmail", source = "user.email")
    @Mapping(target = "orderNumber", expression = "java(OrderResponse.formatOrderNumber(order.getId()))")
    @Mapping(target = "allowedNextStatuses", expression = "java(new java.util.ArrayList<>(order.getStatus().allowedNext()))")
    @Mapping(target = "totalItems", expression = "java(order.getTotalItems())")
    OrderResponse toOrderResponse(Order order);

    List<OrderResponse> toOrderResponseList(List<Order> orders);

    /*
     * `imageUrl` comes from the current product - a product image being updated should be
     * reflected everywhere, including in old orders. Contrast with `productName` and
     * `unitPrice`, which are read from the ORDER LINE's own snapshot columns and must
     * never come from the product.
     *
     * The generated code must therefore call `item.getProductName()` and
     * `item.getUnitPrice()` - the entity's fields - not `item.getProduct().getName()`.
     * MapStruct does exactly that, because it matches by property name on the source type,
     * which is precisely the behaviour we want here and worth verifying in the generated
     * source the first time.
     */
    @Mapping(target = "productId", source = "product.id")
    @Mapping(target = "imageUrl", source = "product.imageUrl")
    OrderItemResponse toOrderItemResponse(OrderItem item);

    List<OrderItemResponse> toOrderItemResponseList(List<OrderItem> items);

    // =================================================================
    //  Helpers with logic the expression language cannot express
    // =================================================================

    /**
     * Whether every line of a cart can be fulfilled right now.
     *
     * <p>A {@code default} method rather than an inline {@code expression}, because a
     * three-clause predicate inside a string is unreadable and untestable. Written as a
     * default method it is ordinary Java: the mapper interface stays the only class that
     * knows both the entity and the DTO, and this rule is testable directly.
     *
     * <p>An empty cart is <b>not</b> ready to check out even though every line in it is
     * trivially fulfillable. Checking out nothing is not a meaningful operation, and
     * returning true here would let a UI enable a button that leads to a 400.
     */
    default boolean isCheckoutReady(Cart cart) {
        if (cart == null || cart.isEmpty()) {
            return false;
        }
        return cart.getItems().stream().allMatch(CartItem::isFulfillable);
    }

    /**
     * Null-safe money for the rare case a mapper needs to default a value.
     *
     * <p>Used by the generated implementations for any nullable {@code BigDecimal} target,
     * so an unset price maps to {@code 0.00} rather than to null - which matters because
     * the frontend would render a null price as "₹NaN".
     */
    default BigDecimal mapMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Passes a value through, giving MapStruct an explicit hook for custom handling. */
    @Named("identityString")
    default String identityString(String value) {
        return value;
    }
}
