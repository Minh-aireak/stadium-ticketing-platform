-- ============================================================
-- V3: add role to accounts (RBAC — USER/ADMIN)
-- ============================================================

ALTER TABLE accounts ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER';
