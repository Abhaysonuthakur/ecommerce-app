-- =====================================================================
--  Project 5 — Professional E-Commerce Application
--  MySQL 8.0 schema
--
--  Run with:
--    mysql -u root -p --default-character-set=utf8mb4 < db/schema.sql
--
--  Design notes are inline. The short version:
--    * users/categories/products are the "master" tables
--    * carts/cart_items are transient working state
--    * orders/order_items are an immutable historical record
--    * money is DECIMAL(19,2) everywhere - never FLOAT/DOUBLE
-- =====================================================================

DROP DATABASE IF EXISTS ecommerce_db;
CREATE DATABASE ecommerce_db
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;
USE ecommerce_db;

-- =====================================================================
--  USERS
-- =====================================================================
--  One row per person, regardless of how they signed up.
--
--  `password` is NULLABLE on purpose. A Google user never has a local
--  password; forcing a non-null column would mean inventing a fake hash
--  for them, which is a credential that should not exist.
--
--  `role` is backed by a CHECK constraint as well as the Java enum.
--  The Java enum protects the application path; the CHECK protects the
--  database from a bad migration or a manual UPDATE - and those are
--  exactly the changes nobody tests.
-- =====================================================================
CREATE TABLE users (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    email       VARCHAR(150) NOT NULL,
    password    VARCHAR(100) NULL COMMENT 'BCrypt hash. NULL for social-login-only accounts.',
    phone       VARCHAR(20)  NULL,
    address     VARCHAR(255) NULL,
    role        VARCHAR(20)  NOT NULL DEFAULT 'CUSTOMER',
    provider    VARCHAR(20)  NOT NULL DEFAULT 'LOCAL' COMMENT 'LOCAL | GOOGLE',
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    --     utf8mb4_0900_ai_ci is case-insensitive, so this index makes
    --     'Ada@Example.com' and 'ada@example.com' the same account.
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT chk_users_role     CHECK (role     IN ('CUSTOMER','ADMIN')),
    CONSTRAINT chk_users_provider CHECK (provider IN ('LOCAL','GOOGLE'))
) ENGINE = InnoDB;

CREATE INDEX idx_users_role ON users (role);

-- =====================================================================
--  CATEGORIES
-- =====================================================================
--  Soft delete via `active`, because a category that has ever held a
--  product must not disappear: order history and product rows point at
--  it. `slug` is the stable, URL-safe handle so a rename does not break
--  a bookmarked link.
-- =====================================================================
CREATE TABLE categories (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    slug        VARCHAR(120) NOT NULL,
    description VARCHAR(500) NULL,
    image_url   VARCHAR(500) NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_categories_name UNIQUE (name),
    CONSTRAINT uk_categories_slug UNIQUE (slug)
) ENGINE = InnoDB;

CREATE INDEX idx_categories_active ON categories (active);

-- =====================================================================
--  PRODUCTS
-- =====================================================================
--  Many products belong to one category  ->  category_id FK, indexed,
--  because "show me the products in category 3" is the single most
--  common query in the whole application.
--
--  ON DELETE RESTRICT: you cannot delete a category that still has
--  products. Deactivating is the supported path. Cascading here would
--  silently delete catalogue data, which is never what anyone wants.
--
--  `stock` is INT UNSIGNED with a CHECK >= 0. The unsigned type is the
--  last line of defence against a negative stock bug: even if the
--  service layer were wrong, the database refuses the write.
-- =====================================================================
CREATE TABLE products (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    name        VARCHAR(200)  NOT NULL,
    description VARCHAR(2000) NULL,
    price       DECIMAL(19,2) NOT NULL,
    stock       INT UNSIGNED  NOT NULL DEFAULT 0,
    image_url   VARCHAR(500)  NULL,
    category_id BIGINT        NOT NULL,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_products_category
        FOREIGN KEY (category_id) REFERENCES categories (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_products_price CHECK (price >= 0),
    --  Not redundant with INT UNSIGNED, and not decoration.
    --
    --  INT UNSIGNED already refuses a negative value with ER_WARN_DATA_OUT_OF_RANGE
    --  ("Out of range value for column 'stock'"), which was verified by hand:
    --      UPDATE products SET stock = 0 - 5 WHERE id = 1;
    --      ERROR 1264 (22003): Out of range value for column 'stock' at row 1
    --
    --  The CHECK exists so the constraint is VISIBLE in SHOW CREATE TABLE and in
    --  information_schema.CHECK_CONSTRAINTS. That matters more than the rejection
    --  itself: a developer reading the schema sees the rule instead of having to
    --  know that UNSIGNED implies it. It also survives a future change of column
    --  type to a signed int, which would silently remove the protection.
    --
    --  This line was MISSING from the deployed database while the comment above
    --  claimed it was present. That is the exact class of drift `ddl-auto: validate`
    --  exists to catch and cannot, because validate checks tables and columns - not
    --  constraints. Found while investigating an oversell bug, which no CHECK would
    --  have prevented anyway: the bad state was two ORDER ROWS for one unit of
    --  stock, and stock was never negative.
    CONSTRAINT chk_products_stock CHECK (stock >= 0)
) ENGINE = InnoDB;

--  ---------------------------------------------------------------------
--  The constraint above was added to schema.sql AFTER the database was
--  created, so an existing database does not have it. Reconcile with:
--
--      ALTER TABLE products
--          ADD CONSTRAINT chk_products_stock CHECK (stock >= 0);
--
--  Verify against the live schema rather than the file - that discrepancy
--  is what made this comment necessary:
--
--      SELECT CONSTRAINT_NAME, CHECK_CLAUSE
--        FROM information_schema.CHECK_CONSTRAINTS
--       WHERE CONSTRAINT_SCHEMA = 'ecommerce_db';
--  ---------------------------------------------------------------------

CREATE INDEX idx_products_category    ON products (category_id);
CREATE INDEX idx_products_active      ON products (active);
-- Composite: the list endpoint filters on active AND sorts/filters by
-- category far more often than it uses either alone.
CREATE INDEX idx_products_active_cat  ON products (active, category_id);
CREATE INDEX idx_products_name        ON products (name);
CREATE INDEX idx_products_price       ON products (price);

-- =====================================================================
--  CARTS   (User 1:1 Cart)
-- =====================================================================
--  UNIQUE (user_id) is what makes it one-cart-per-user. Without that
--  index the "find or create" logic has a race: two simultaneous
--  requests both find nothing and both insert.
-- =====================================================================
CREATE TABLE carts (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_carts_user UNIQUE (user_id),
    CONSTRAINT fk_carts_user
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE
) ENGINE = InnoDB;

-- =====================================================================
--  CART_ITEMS   (Cart 1:N CartItem, Product 1:N CartItem)
-- =====================================================================
--  UNIQUE (cart_id, product_id): adding the same product twice must
--  raise the quantity of one row, not create a second row. Enforcing it
--  here means a logic bug in the service cannot produce a cart showing
--  the same product twice.
-- =====================================================================
CREATE TABLE cart_items (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    cart_id    BIGINT      NOT NULL,
    product_id BIGINT      NOT NULL,
    quantity   INT         NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_cart_items_cart_product UNIQUE (cart_id, product_id),
    CONSTRAINT fk_cart_items_cart
        FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_items_product
        FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE,
    CONSTRAINT chk_cart_items_quantity CHECK (quantity > 0)
) ENGINE = InnoDB;

-- =====================================================================
--  ORDERS   (User 1:N Order)
-- =====================================================================
--  Historical record. Note:
--    * total_amount is stored, not recomputed on read. A later product
--      price change must not change what the customer owed.
--    * the address is DENORMALISED (copied) rather than referenced.
--      If the customer moves house, an old order must still show the
--      address it was shipped to.
--    * ON DELETE RESTRICT on user_id: deleting a customer must not
--      silently erase their order history.
-- =====================================================================
CREATE TABLE orders (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    user_id          BIGINT        NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    total_amount     DECIMAL(19,2) NOT NULL,
    shipping_address VARCHAR(255)  NULL,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_orders_user
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_orders_total  CHECK (total_amount >= 0),
    CONSTRAINT chk_orders_status CHECK (status IN
        ('PENDING','CONFIRMED','PROCESSING','SHIPPED','DELIVERED','CANCELLED'))
) ENGINE = InnoDB;

CREATE INDEX idx_orders_user      ON orders (user_id);
CREATE INDEX idx_orders_status    ON orders (status);
-- Admin's default view is "newest orders first", so the sort column is
-- in the index.
CREATE INDEX idx_orders_created   ON orders (created_at);
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at);

-- =====================================================================
--  ORDER_ITEMS   (Order 1:N OrderItem, Product 1:N OrderItem)
-- =====================================================================
--  This is the table that makes order history correct.
--
--  `unit_price` is a SNAPSHOT taken at purchase time. We store it
--  alongside product_id rather than joining to products.price:
--
--      products.price = 1500  ->  order placed
--      order_items.unit_price = 1500     <-- frozen
--      products.price = 1800  ->  modified later
--      order_items.unit_price = 1500     <-- still correct
--
--  `product_name` is snapshotted for the same reason: a renamed or
--  deleted product must not make an old invoice unreadable.
--
--  ON DELETE RESTRICT on product_id: a product that has ever been sold
--  cannot be hard-deleted. Deactivate it instead. This is the constraint
--  that makes the "delete" endpoint check whether the product is safe.
-- =====================================================================
CREATE TABLE order_items (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    order_id     BIGINT        NOT NULL,
    product_id   BIGINT        NOT NULL,
    product_name VARCHAR(200)  NOT NULL COMMENT 'Snapshot of the product name at purchase time',
    unit_price   DECIMAL(19,2) NOT NULL COMMENT 'Snapshot of the price at purchase time',
    quantity     INT           NOT NULL,
    subtotal     DECIMAL(19,2) NOT NULL COMMENT 'unit_price * quantity, computed by the server',
    created_at   DATETIME(6)   NOT NULL,
    updated_at   DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order
        FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_items_product
        FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT,
    CONSTRAINT chk_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT chk_order_items_price    CHECK (unit_price >= 0),
    CONSTRAINT chk_order_items_subtotal CHECK (subtotal >= 0)
) ENGINE = InnoDB;

CREATE INDEX idx_order_items_order   ON order_items (order_id);
CREATE INDEX idx_order_items_product ON order_items (product_id);

-- =====================================================================
--  Entity-relationship summary
-- =====================================================================
--
--   users 1 ──────── N orders          (a customer places many orders)
--   users 1 ──────── 1 carts           (one active cart each)
--   users 1 ──────── N order_items     (derived: through orders)
--   carts 1 ──────── N cart_items
--   categories 1 ─── N products
--   products 1 ───── N cart_items
--   products 1 ───── N order_items
--   orders 1 ─────── N order_items
--
-- =====================================================================
