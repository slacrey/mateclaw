-- V130: Account expiry. NULL means the account is permanent.
SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_user'
      AND COLUMN_NAME = 'expires_at'
);
SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE mate_user ADD COLUMN expires_at DATETIME DEFAULT NULL COMMENT ''Account expiry time; NULL means permanent'' AFTER enabled',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;
