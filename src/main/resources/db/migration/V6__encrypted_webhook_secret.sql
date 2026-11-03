-- =====================================================================================
-- V6 - The webhook secret is stored encrypted.
--
-- Hashing it (as for a password) is impossible: we need the original value to sign each
-- webhook. So it is encrypted by the application with AES-256-GCM (WebhookSecretCipher). The
-- key lives outside the database, so a stolen backup or a SQL injection does not reveal the
-- secrets.
--
-- Existing values are not converted: this proof of concept has no webhook secret in its demo
-- data. On a real database, a one-off job would encrypt them before this column is read.
-- =====================================================================================

ALTER TABLE retailer
    RENAME COLUMN webhook_secret TO webhook_secret_encrypted;

ALTER TABLE retailer
    ALTER COLUMN webhook_secret_encrypted TYPE VARCHAR(512);
