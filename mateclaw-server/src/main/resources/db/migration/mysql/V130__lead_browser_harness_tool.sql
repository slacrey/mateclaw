-- V130: Register lead-acquisition browser harness as a bindable built-in tool.
-- Kept as a dedicated tool row so lead agents can opt in without changing the
-- generic browser-control contract for every agent.

INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, disclosure_tier, create_time, update_time, deleted)
VALUES (
    1000000023,
    'LeadBrowserHarnessTool',
    'Lead Browser Harness',
    'Task-level browser harnesses for lead-acquisition agents: cross-site visible search plus filter/sort option selection, Douyin presets, and compact current-page lead snapshots.',
    'builtin',
    'leadBrowserHarnessTool',
    '🎯',
    TRUE,
    TRUE,
    'core',
    NOW(),
    NOW(),
    0
)
ON DUPLICATE KEY UPDATE
    name=VALUES(name),
    display_name=VALUES(display_name),
    description=VALUES(description),
    bean_name=VALUES(bean_name),
    icon=VALUES(icon),
    disclosure_tier=VALUES(disclosure_tier),
    update_time=VALUES(update_time);
