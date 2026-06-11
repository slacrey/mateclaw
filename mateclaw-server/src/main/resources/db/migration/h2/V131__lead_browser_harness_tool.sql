-- V131: Register lead-acquisition browser harness as a bindable built-in tool.
-- Kept as a dedicated tool row so lead agents can opt in without changing the
-- generic browser-control contract for every agent.

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, disclosure_tier, create_time, update_time, deleted)
KEY (id)
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
);
