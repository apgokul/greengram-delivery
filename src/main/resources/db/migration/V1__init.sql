CREATE SEQUENCE IF NOT EXISTS order_sequence START WITH 1000 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS customers (
    id BIGSERIAL PRIMARY KEY,
    bsuid VARCHAR(128) UNIQUE,
    whatsapp_number VARCHAR(255) UNIQUE,
    name VARCHAR(255),
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    address_notes VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS delivery_runs (
    id BIGSERIAL PRIMARY KEY,
    rider_phone_number VARCHAR(255),
    dispatched_at TIMESTAMP WITHOUT TIME ZONE,
    status VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS products (
    id BIGSERIAL PRIMARY KEY,
    sku VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255),
    current_price NUMERIC(38, 2),
    stock_quantity INTEGER NOT NULL,
    quantity NUMERIC(38, 2),
    unit VARCHAR(255),
    version BIGINT,
    is_active BOOLEAN NOT NULL
);

CREATE TABLE IF NOT EXISTS customer_orders (
    id BIGINT PRIMARY KEY,
    display_id VARCHAR(255) NOT NULL UNIQUE,
    customer_id BIGINT,
    status VARCHAR(255),
    total_amount NUMERIC(38, 2),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    ready_at TIMESTAMP WITHOUT TIME ZONE,
    delivered_at TIMESTAMP WITHOUT TIME ZONE,
    delivery_latitude DOUBLE PRECISION,
    delivery_longitude DOUBLE PRECISION,
    delivery_run_id BIGINT,
    CONSTRAINT fk_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_delivery_run FOREIGN KEY (delivery_run_id) REFERENCES delivery_runs (id)
);

CREATE INDEX IF NOT EXISTS idx_customer_status ON customer_orders (customer_id, status);

CREATE TABLE IF NOT EXISTS order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT,
    product_id BIGINT,
    quantity INTEGER,
    locked_price NUMERIC(38, 2),
    CONSTRAINT fk_order FOREIGN KEY (order_id) REFERENCES customer_orders (id),
    CONSTRAINT fk_product FOREIGN KEY (product_id) REFERENCES products (id)
);

CREATE TABLE IF NOT EXISTS inventory_audit_logs (
    id BIGSERIAL PRIMARY KEY,
    sku VARCHAR(255) NOT NULL,
    old_stock INTEGER NOT NULL,
    new_stock INTEGER NOT NULL,
    change_amount INTEGER NOT NULL,
    reason VARCHAR(255) NOT NULL,
    changed_by VARCHAR(255) NOT NULL,
    timestamp TIMESTAMP WITHOUT TIME ZONE NOT NULL
);
