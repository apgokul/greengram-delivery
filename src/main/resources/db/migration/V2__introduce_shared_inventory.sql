-- Cosmetic Cleanup:
UPDATE products SET name = TRIM(name);

-- Schema Changes:
CREATE TABLE IF NOT EXISTS inventories (
    id BIGSERIAL PRIMARY KEY,
    base_sku VARCHAR(255) UNIQUE NOT NULL,
    stock_quantity NUMERIC(38, 3) NOT NULL,
    unit VARCHAR(255) NOT NULL,
    version BIGINT
);

-- Add inventory_id to products.
ALTER TABLE products ADD COLUMN inventory_id BIGINT;

-- Data Migration:
INSERT INTO inventories (base_sku, stock_quantity, unit, version)
SELECT 
    CASE WHEN POSITION('_' IN sku) > 0 THEN SUBSTRING(sku FROM 1 FOR POSITION('_' IN sku) - 1) ELSE sku END AS base_sku,
    CAST(SUM(
        CASE 
            WHEN unit = 'G' THEN (stock_quantity * quantity) / 1000.0
            WHEN unit = 'KG' THEN (stock_quantity * quantity)
            WHEN unit = 'ML' THEN (stock_quantity * quantity) / 1000.0
            WHEN unit = 'L' THEN (stock_quantity * quantity)
            ELSE (stock_quantity * quantity)
        END
    ) AS NUMERIC(38, 3)) AS stock_quantity,
    CASE 
        WHEN MAX(CASE WHEN unit IN ('G', 'KG') THEN 1 ELSE 0 END) = 1 THEN 'KG'
        WHEN MAX(CASE WHEN unit IN ('ML', 'L') THEN 1 ELSE 0 END) = 1 THEN 'L'
        ELSE MAX(unit)
    END AS unit,
    0 AS version
FROM products
GROUP BY CASE WHEN POSITION('_' IN sku) > 0 THEN SUBSTRING(sku FROM 1 FOR POSITION('_' IN sku) - 1) ELSE sku END;

-- Link products to their new inventories row
UPDATE products
SET inventory_id = (
    SELECT i.id FROM inventories i 
    WHERE (CASE WHEN POSITION('_' IN products.sku) > 0 THEN SUBSTRING(products.sku FROM 1 FOR POSITION('_' IN products.sku) - 1) ELSE products.sku END) = i.base_sku
);

-- Cleanup:
ALTER TABLE products DROP COLUMN stock_quantity;
ALTER TABLE products RENAME COLUMN quantity TO package_quantity;
ALTER TABLE products RENAME COLUMN unit TO package_unit;

-- Add necessary foreign key constraints and indexes.
ALTER TABLE products ADD CONSTRAINT fk_inventory FOREIGN KEY (inventory_id) REFERENCES inventories (id);
CREATE INDEX idx_products_inventory_id ON products(inventory_id);
