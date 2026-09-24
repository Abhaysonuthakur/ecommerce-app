package com.shop.ecommerce.mapper;

import com.shop.ecommerce.dto.category.CategoryRequest;
import com.shop.ecommerce.dto.product.ProductRequest;
import com.shop.ecommerce.entity.Category;
import com.shop.ecommerce.entity.Product;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

/**
 * Request DTO to entity conversion, for the two types an admin writes.
 *
 * <h2>Why this is a separate interface from {@code EntityMapper}</h2>
 *
 * <p>Read and write mapping have opposite rules, and mixing them in one interface makes
 * both harder to reason about:
 *
 * <ul>
 *   <li><b>Read</b> mapping is wide: map everything the client may see, and let
 *       {@code unmappedTargetPolicy = ERROR} catch anything the DTO adds later.</li>
 *   <li><b>Write</b> mapping is narrow and paranoid: map only the fields a client is
 *       allowed to set, and name every field that must <em>not</em> be settable.</li>
 * </ul>
 *
 * <p>Keeping them apart also means the security-relevant ignores are all in one file, where
 * they can be reviewed together - which is exactly how the assembly of "what can a client
 * write?" should be audited.
 *
 * <h2>There is deliberately no {@code toEntity(RegisterRequest)}</h2>
 *
 * <p>Creating a user account means deciding a BCrypt hash, an authentication provider, a
 * default role, and an enabled flag - three of which depend on <em>which</em> authentication
 * path the user took. A mapper cannot express that: it would either guess or accumulate
 * conditionals, and either way the security decision would end up in a generated class
 * instead of the service where it can be read and tested. {@code AuthServiceImpl.register}
 * constructs the {@code User} by hand, and the role is a literal there.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface EntityWriteMapper {

    // =================================================================
    //  Category
    // =================================================================

    /*
     * Mapped: name, description, imageUrl, active - exactly the four fields a client may
     * set, matching CategoryRequest one-for-one.
     *
     * The five ignores below are the security surface, named explicitly rather than left to
     * a policy default:
     *   id, createdAt, updatedAt - server-owned. A client that could set `createdAt` could
     *                              forge an audit trail.
     *   slug                     - derived from the name by the service, so it stays a
     *                              stable link handle and cannot collide with another row.
     *   products                 - the collection is navigated through Product, never
     *                              wholesale-replaced by a request body (which would delete
     *                              every product it omitted, via orphanRemoval).
     *
     * With unmappedTargetPolicy = ERROR these are not optional: the build fails until every
     * target field is either sourced or declared off-limits. That is the whole value of the
     * setting - it converts "did we remember to ignore the dangerous fields?" from a review
     * question into a compile error.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "slug", ignore = true)
    @Mapping(target = "products", ignore = true)
    Category toCategoryEntity(CategoryRequest request);

    /**
     * Copies a request onto an existing category.
     *
     * <p>{@code @MappingTarget} writes into the instance you pass in rather than creating a
     * new one. That matters for the persistence context: the entity stays managed and
     * Hibernate's dirty checking produces the smallest possible UPDATE, and the same Java
     * object remains referenced by anything else holding it.
     *
     * <p>{@code slug} and the audit fields are ignored here too - and this is the important
     * one. The slug must not be regenerated on an update: it is a stable link handle, and
     * regenerating it on every rename would break every bookmark the moment somebody fixes
     * a typo. {@code createdAt} is additionally {@code updatable = false} at the column
     * level, so even a mistake here could not change it.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "slug", ignore = true)
    @Mapping(target = "products", ignore = true)
    void updateCategoryEntity(CategoryRequest request, @MappingTarget Category category);

    // =================================================================
    //  Product
    // =================================================================

    /*
     * `category` is deliberately ignored: the request carries a `categoryId`, which is a
     * Long, and the target needs a `Category` entity. A mapper that turned an id into an
     * entity would have to query the database - and a mapper that queries is a mapper that
     * needs a database to test and that hides an N+1 in the least obvious place.
     *
     * The service resolves the category (returning a proper 404 if it does not exist) and
     * sets it. That is a business decision with an error case, so it belongs in the service.
     *
     * `id`, `createdAt` and `updatedAt` are server-owned: a client must not be able to set
     * a primary key or forge a timestamp.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "category", ignore = true)
    Product toProductEntity(ProductRequest request);

    /**
     * Copies a request onto an existing product.
     *
     * <p>{@code category} is ignored again, for the same reason - the service resolves it.
     *
     * <p><b>A PUT update with no defaults is deliberate.</b> If {@code active} were
     * defaulted here, an admin updating only a product's price would silently reactivate a
     * product that had been withdrawn from sale. For an update, "the client did not send
     * this field" must mean "leave it alone", never "set it to a default". The service
     * handles the boxed Boolean explicitly.
     *
     * <p>Contrast with {@code create}, where a default <em>is</em> right: a new product
     * with no stated active flag should be on sale.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "category", ignore = true)
    void updateProductEntity(ProductRequest request, @MappingTarget Product product);
}
