-- V4__order_cancellation_and_inventory_snapshots.sql
-- Supports safe multi-actor cancellations, snapshot inventory deductions, and legacy reconciliation tracking

-- 1. Add cancellation details and stock reconciliation tracking to customer_orders
ALTER TABLE customer_orders ADD COLUMN IF NOT EXISTS cancellation_reason VARCHAR(255);
ALTER TABLE customer_orders ADD COLUMN IF NOT EXISTS stock_reconciled BOOLEAN NOT NULL DEFAULT TRUE;

-- 2. Add deducted inventory snapshot quantity and per-item reconciliation flag to order_items
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS deducted_quantity NUMERIC(38, 3);
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS stock_reconciliation_required BOOLEAN NOT NULL DEFAULT FALSE;
