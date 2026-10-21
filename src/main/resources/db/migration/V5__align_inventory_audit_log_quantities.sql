-- V5__align_inventory_audit_log_quantities.sql
-- Align inventory audit log quantity columns with shared physical inventory precision NUMERIC(38, 3)

ALTER TABLE inventory_audit_logs ALTER COLUMN old_stock TYPE NUMERIC(38, 3);
ALTER TABLE inventory_audit_logs ALTER COLUMN new_stock TYPE NUMERIC(38, 3);
ALTER TABLE inventory_audit_logs ALTER COLUMN change_amount TYPE NUMERIC(38, 3);
