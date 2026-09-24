package com.shop.ecommerce.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * A product category, e.g. "Shirts".
 *
 * <h2>Relationship owned by this entity</h2>
 *
 * <pre>
 *   Category 1 ──────── N Product     (a category contains many products)
 * </pre>
 *
 * <p>{@link Product} holds {@code category_id}, so {@code Product} owns the
 * association and the collection here is the inverse, marked {@code mappedBy = "category"}.
 *
 * <p><b>WHY a separate table instead of a {@code category} string column on products?</b>
 * A string column duplicates the name on every row, so renaming a category means
 * updating thousands of rows, and a typo silently creates a new "category". A foreign
 * key makes the rename one row and makes an invalid category impossible.
 *
 * <p><b>WHY soft delete?</b> Because a category that has ever classified a product
 * cannot be removed without orphaning that product - and the database enforces this
 * with {@code ON DELETE RESTRICT}, proven in {@code db/constraint-test.sql}. Setting
 * {@code active = false} hides it from shoppers while leaving every existing product
 * and order line intact. The {@code active} flag is the only deletion mechanism the
 * API exposes for a category that is in use.
 */
@Entity
@Table(
        name = "categories",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_categories_name", columnNames = "name"),
                @UniqueConstraint(name = "uk_categories_slug", columnNames = "slug")
        },
        indexes = @Index(name = "idx_categories_active", columnList = "active")
)
@Getter
@Setter
@NoArgsConstructor
@ToString(onlyExplicitlyIncluded = true, callSuper = true)
public class Category extends BaseEntity {

    @ToString.Include
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * A URL-safe handle: {@code "Men's Shirts"} becomes {@code "mens-shirts"}.
     *
     * <p><b>WHY keep a slug when we already have an id?</b> Because links like
     * {@code /category/mens-shirts} survive a rename of the display name, and are
     * readable in analytics. Generated in the service layer from the name, and stored
     * so it stays stable - regenerating on every update would break every existing
     * link the moment someone fixes a typo.
     */
    @ToString.Include
    @Column(name = "slug", nullable = false, length = 120)
    private String slug;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /** False means hidden from shoppers, but still referenced by existing products. */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    // =================================================================
    //  Category 1:N Product
    // =================================================================
    /**
     * <b>One category contains many products.</b>
     *
     * <p>{@code mappedBy = "category"} points at the field on {@link Product}.
     *
     * <p>{@code cascade = ALL} propagates persistence: saving a category cascades to
     * its products. It does <b>not</b> propagate deletion in a way that could surprise
     * anyone, because the database's {@code ON DELETE RESTRICT} refuses to delete a
     * category that still has products - and that restriction is checked before this
     * cascade could do anything destructive.
     *
     * <p>{@code LAZY} is stated explicitly. {@code EAGER} here would mean that listing
     * categories - a tiny, cheap, rare operation - drags every product in the catalogue
     * into memory. That is the classic N+1-shaped performance bug.
     */
    @OneToMany(mappedBy = "category", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<Product> products = new ArrayList<>();

    /** Keeps both sides consistent in memory. */
    public void addProduct(Product product) {
        products.add(product);
        product.setCategory(this);
    }
}
