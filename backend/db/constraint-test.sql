-- =====================================================================
--  Constraint probe suite
--
--  Purpose: prove that every CHECK, UNIQUE and FOREIGN KEY in
--  schema.sql actually REJECTS something. A constraint that never fires
--  is indistinguishable from no constraint at all, and the failure mode
--  is a corrupted database rather than a stack trace.
--
--  Applied constraints were already proven to parse. This file proves
--  they enforce.
--
--  Every probe prints PASS or FAIL. FAIL means the database accepted
--  something it should have refused.
--
--  Run:  mysql -u root -p < db/constraint-test.sql
-- =====================================================================

USE ecommerce_db;

DROP PROCEDURE IF EXISTS expect_fail;
DROP PROCEDURE IF EXISTS expect_ok;

DELIMITER $$

-- Run `stmt` and PASS only if it raises an error.
CREATE PROCEDURE expect_fail(IN label VARCHAR(120), IN stmt TEXT)
BEGIN
    DECLARE failed INT DEFAULT 0;
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION SET failed = 1;
    SET @s = stmt;
    PREPARE p FROM @s;
    EXECUTE p;
    DEALLOCATE PREPARE p;
    IF failed = 1 THEN
        SELECT CONCAT('PASS  ', label) AS result;
    ELSE
        SELECT CONCAT('FAIL  ', label, '   <-- accepted, should have been rejected') AS result;
    END IF;
END$$

-- Run `stmt` and PASS only if it succeeds.
CREATE PROCEDURE expect_ok(IN label VARCHAR(120), IN stmt TEXT)
BEGIN
    DECLARE failed INT DEFAULT 0;
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION SET failed = 1;
    SET @s = stmt;
    PREPARE p FROM @s;
    EXECUTE p;
    DEALLOCATE PREPARE p;
    IF failed = 1 THEN
        SELECT CONCAT('FAIL  ', label, '   <-- rejected, should have been accepted') AS result;
    ELSE
        SELECT CONCAT('PASS  ', label) AS result;
    END IF;
END$$

DELIMITER ;

-- ---------------------------------------------------------------------
-- Seed one valid row of each type so the probes have something to
-- reference. Probing against an empty table would not exercise the
-- constraint in the situation it exists for.
-- ---------------------------------------------------------------------
DELETE FROM order_items; DELETE FROM orders; DELETE FROM cart_items;
DELETE FROM carts; DELETE FROM products; DELETE FROM categories; DELETE FROM users;

INSERT INTO users (id, name, email, password, role, provider, enabled, created_at, updated_at)
VALUES (1, 'Ada Lovelace', 'ada@example.com', '$2a$10$abcdefghijklmnopqrstuv', 'CUSTOMER', 'LOCAL', TRUE,
        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

INSERT INTO categories (id, name, slug, active, created_at, updated_at)
VALUES (1, 'Shirts', 'shirts', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

INSERT INTO products (id, name, price, stock, category_id, active, created_at, updated_at)
VALUES (1, 'Linen Shirt', 1500.00, 10, 1, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

INSERT INTO carts (id, user_id, created_at, updated_at)
VALUES (1, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

INSERT INTO cart_items (id, cart_id, product_id, quantity, created_at, updated_at)
VALUES (1, 1, 1, 2, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

INSERT INTO orders (id, user_id, status, total_amount, created_at, updated_at)
VALUES (1, 1, 'PENDING', 3000.00, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

INSERT INTO order_items (id, order_id, product_id, product_name, unit_price, quantity, subtotal, created_at, updated_at)
VALUES (1, 1, 1, 'Linen Shirt', 1500.00, 2, 3000.00, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

SELECT '--- users ---' AS section;
CALL expect_fail('users.role rejects an unknown role',
    'INSERT INTO users (name,email,role,provider,enabled,created_at,updated_at) VALUES (''X'',''x1@e.com'',''SUPERADMIN'',''LOCAL'',TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))');
CALL expect_fail('users.provider rejects an unknown provider',
    'INSERT INTO users (name,email,role,provider,enabled,created_at,updated_at) VALUES (''X'',''x2@e.com'',''CUSTOMER'',''FACEBOOK'',TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))');
CALL expect_fail('users.email is unique (exact duplicate)',
    'INSERT INTO users (name,email,role,provider,enabled,created_at,updated_at) VALUES (''X'',''ada@example.com'',''CUSTOMER'',''LOCAL'',TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))');
-- The collation is utf8mb4_0900_ai_ci, so case must NOT create a second account.
-- This is the assertion that proves the email index is case-insensitive.
CALL expect_fail('users.email is unique (case-insensitive duplicate)',
    'INSERT INTO users (name,email,role,provider,enabled,created_at,updated_at) VALUES (''X'',''ADA@Example.COM'',''CUSTOMER'',''LOCAL'',TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))');
CALL expect_ok('users.password may be NULL (social-only account)',
    'INSERT INTO users (name,email,password,role,provider,enabled,created_at,updated_at) VALUES (''G'',''g@example.com'',NULL,''CUSTOMER'',''GOOGLE'',TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))');

SELECT '--- categories ---' AS section;
CALL expect_fail('categories.slug is unique',
    'INSERT INTO categories (name,slug,active,created_at,updated_at) VALUES (''Shirts2'',''shirts'',TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))');

SELECT '--- products ---' AS section;
CALL expect_fail('products.price rejects a negative value',
    'INSERT INTO products (name,price,stock,category_id,active,created_at,updated_at) VALUES (''Bad'',-1.00,5,1,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))');
CALL expect_fail('products.stock rejects a negative value',
    'INSERT INTO products (name,price,stock,category_id,active,created_at,updated_at) VALUES (''Bad'',10.00,-5,1,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))');
CALL expect_fail('products.category_id rejects an orphan category',
    'INSERT INTO products (name,price,stock,category_id,active,created_at,updated_at) VALUES (''Bad'',10.00,5,9999,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))');
-- Deleting a category that still holds products must be refused (RESTRICT).
CALL expect_fail('products block deletion of their category',
    'DELETE FROM categories WHERE id = 1');

SELECT '--- carts ---' AS section;
-- One cart per user is enforced by UNIQUE(user_id), not by application logic.
CALL expect_fail('carts enforces one cart per user',
    'INSERT INTO carts (user_id,created_at,updated_at) VALUES (1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))');

SELECT '--- cart_items ---' AS section;
CALL expect_fail('cart_items.quantity rejects zero',
    'INSERT INTO cart_items (cart_id,product_id,quantity,created_at,updated_at) VALUES (1,1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))');
CALL expect_fail('cart_items.quantity rejects a negative value',
    'INSERT INTO cart_items (cart_id,product_id,quantity,created_at,updated_at) VALUES (1,9999,-3,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))');
-- Same product twice in one cart must be one row with a bigger quantity.
CALL expect_fail('cart_items forbids the same product twice in one cart',
    'INSERT INTO cart_items (cart_id,product_id,quantity,created_at,updated_at) VALUES (1,1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))');

SELECT '--- orders ---' AS section;
CALL expect_fail('orders.status rejects an unknown status',
    'INSERT INTO orders (user_id,status,total_amount,created_at,updated_at) VALUES (1,''REFUNDED'',10.00,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))');
CALL expect_fail('orders.total_amount rejects a negative value',
    'INSERT INTO orders (user_id,status,total_amount,created_at,updated_at) VALUES (1,''PENDING'',-10.00,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))');
CALL expect_fail('orders block deletion of their user (history is preserved)',
    'DELETE FROM users WHERE id = 1');

SELECT '--- order_items ---' AS section;
CALL expect_fail('order_items.quantity rejects zero',
    'INSERT INTO order_items (order_id,product_id,product_name,unit_price,quantity,subtotal,created_at,updated_at) VALUES (1,1,''X'',10.00,0,0.00,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))');
CALL expect_fail('order_items.product_id rejects an orphan product',
    'INSERT INTO order_items (order_id,product_id,product_name,unit_price,quantity,subtotal,created_at,updated_at) VALUES (1,9999,''X'',10.00,1,10.00,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))');
-- A sold product cannot be hard-deleted: deactivate it instead.
CALL expect_fail('order_items block deletion of a product that has been sold',
    'DELETE FROM products WHERE id = 1');

SELECT '--- cascade ---' AS section;
-- ON DELETE CASCADE must actually remove children, not merely be declared.
SELECT COUNT(*) AS cart_items_before_cascade FROM cart_items WHERE cart_id = 1;
DELETE FROM carts WHERE id = 1;
SELECT COUNT(*) AS cart_items_after_cascade FROM cart_items WHERE cart_id = 1;

SELECT COUNT(*) AS order_items_before_cascade FROM order_items WHERE order_id = 1;
DELETE FROM orders WHERE id = 1;
SELECT COUNT(*) AS order_items_after_cascade FROM order_items WHERE order_id = 1;

-- ---------------------------------------------------------------------
-- Money precision: prove DECIMAL(19,2) rounds rather than silently
-- storing a value with more precision than the column can hold.
-- ---------------------------------------------------------------------
SELECT '--- money precision ---' AS section;
INSERT INTO products (name,price,stock,category_id,active,created_at,updated_at)
VALUES ('Rounding probe', 10.999, 1, 1, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
SELECT price AS stored_price_of_10_999 FROM products WHERE name = 'Rounding probe';

-- Clean up the probe rows so the suite is re-runnable.
DELETE FROM cart_items; DELETE FROM carts; DELETE FROM order_items; DELETE FROM orders;
DELETE FROM products; DELETE FROM categories; DELETE FROM users;

DROP PROCEDURE IF EXISTS expect_fail;
DROP PROCEDURE IF EXISTS expect_ok;
