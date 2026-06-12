ALTER TABLE mate_lead_template
    ADD COLUMN IF NOT EXISTS match_rules_json CLOB;
