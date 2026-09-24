package com.shop.ecommerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Shared superclass for every entity: the primary key plus the audit timestamps.
 *
 * <p><b>WHY a {@code @MappedSuperclass} rather than an {@code @Entity} with inheritance?</b>
 * A mapped superclass contributes its fields to each subclass's own table. There is no
 * {@code base_entity} table and no join - the columns simply appear in {@code users},
 * {@code products}, and so on. That is exactly what audit fields should be: duplicated
 * storage, not a shared row to join through.
 *
 * <p><b>WHY {@code @EntityListeners(AuditingEntityListener.class)}?</b>
 * It is what makes {@code @CreatedDate} and {@code @LastModifiedDate} work. Without it
 * those annotations are inert and both fields stay null. It also needs
 * {@code @EnableJpaAuditing} somewhere in the application configuration - one without
 * the other silently does nothing.
 *
 * <p><b>WHY {@code Instant} and not {@code LocalDateTime}?</b>
 * An {@code Instant} is an unambiguous point in time. {@code LocalDateTime} has no
 * timezone, so the same value means different moments on different servers. For audit
 * data that answers "when did this happen", ambiguity is the one property you cannot have.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Set once, on insert, by Spring Data's auditing listener.
     *
     * <p>{@code updatable = false} means once written, the column is never included in
     * an UPDATE statement. That turns "createdAt must not change" from a convention into
     * a database-enforced fact.
     */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Refreshed on every update by the auditing listener.
     *
     * <p><b>A trap worth knowing about:</b> this is applied at {@code @PreUpdate}, which
     * fires at <em>flush</em> - and flush happens at commit, after a service method has
     * already built its response. So a service that mutates an entity, maps it, and
     * returns will hand the client the <em>old</em> {@code updatedAt} while the database
     * holds the new one. Every write path in this project therefore calls
     * {@code repository.flush()} before mapping. See {@code ProductServiceImpl.update}.
     */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Equality by primary key, which is what JPA requires.
     *
     * <p>Two transient entities (id == null) are only equal to themselves - never to each
     * other, because two unsaved rows are two different rows. Two persistent entities are
     * equal when their ids match.
     *
     * <p>{@code getClass() != o.getClass()} rather than {@code instanceof}: a Hibernate
     * proxy for a subclass would otherwise compare equal to its parent type.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BaseEntity other = (BaseEntity) o;
        return id != null && id.equals(other.id);
    }

    /**
     * A constant per class, for the same reason JPA requires id-based equality: the hash
     * must not change when the entity is persisted and receives an id. A hash based on
     * {@code id} would break every HashSet containing the entity the moment it is saved.
     */
    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
