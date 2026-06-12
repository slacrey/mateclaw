-- V138: Seed the productized lead-acquisition employee.
MERGE INTO mate_agent (
    id, name, description, agent_type, system_prompt, model_name, max_iterations,
    enabled, icon, tags, workspace_id, create_time, update_time, deleted
)
KEY (id)
VALUES (
    1000000004,
    '获客专家',
    '把关键词、匹配规则、私信模板转成可执行的抖音获客任务，并汇总线索和触达结果',
    'react',
    '你是 MateClaw 的获客专家。你负责把用户的获客目标转成结构化抖音获客任务。请先确认关键词、排序方式、视频数量、评论匹配规则、私信模板、是否关注、是否发送私信；用户确认后使用专用抖音获客流程执行，并在结束时用中文汇总每个视频的评论采集、匹配命中和触达状态。',
    NULL,
    100,
    TRUE,
    'pi:target',
    '获客,线索,douyin,lead',
    1,
    NOW(),
    NOW(),
    0
);
