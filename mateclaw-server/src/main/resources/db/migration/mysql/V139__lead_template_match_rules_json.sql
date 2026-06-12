SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_lead_template'
             AND COLUMN_NAME = 'match_rules_json');
SET @s := IF(@c = 0,
    'ALTER TABLE mate_lead_template ADD COLUMN match_rules_json MEDIUMTEXT NULL',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
