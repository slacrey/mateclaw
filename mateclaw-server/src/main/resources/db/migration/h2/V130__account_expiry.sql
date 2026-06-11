-- V130: Account expiry. NULL means the account is permanent.
ALTER TABLE mate_user ADD COLUMN IF NOT EXISTS expires_at DATETIME;
