-- V131: add Qwen3.7 Plus to the DashScope OpenAI-compatible catalog.
-- Existing databases already ran V99, so the fresh-install seed change alone is not enough.

INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
VALUES (
  1000000607,
  'Qwen3.7 Plus',
  'dashscope-compat',
  'qwen3.7-plus',
  '通义千问 3.7 Plus 旗舰，支持文本、图像与视频输入（兼容模式专属）',
  0.7, 4096, 0.8,
  TRUE, TRUE, FALSE,
  NOW(), NOW(), 0
)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  model_name = VALUES(model_name),
  description = VALUES(description),
  builtin = VALUES(builtin),
  enabled = VALUES(enabled),
  update_time = VALUES(update_time);
