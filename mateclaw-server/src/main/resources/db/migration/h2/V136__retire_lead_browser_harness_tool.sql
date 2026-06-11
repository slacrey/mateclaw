-- V136: Retire the legacy LeadBrowserHarnessTool seed.
-- The Douyin lead-acquisition flow is now represented as a bundled Skill v2
-- package with workflow DSL and schemas instead of a bindable monolithic tool.

UPDATE mate_tool
SET enabled = FALSE,
    deleted = 1,
    update_time = NOW()
WHERE id = 1000000023
   OR name = 'LeadBrowserHarnessTool'
   OR bean_name = 'leadBrowserHarnessTool';

CREATE UNIQUE INDEX IF NOT EXISTS uk_lead_comment_task_comment_key
    ON mate_lead_comment (task_id, comment_key);
