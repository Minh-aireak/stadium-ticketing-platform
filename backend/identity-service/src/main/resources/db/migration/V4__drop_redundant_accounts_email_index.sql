-- ============================================================
-- V4: drop redundant index on accounts(email)
-- ============================================================

-- idx_accounts_email duplicates the implicit index already backing
-- uq_accounts_email UNIQUE(email) — same single column, no reason for two.
DROP INDEX IF EXISTS idx_accounts_email;
