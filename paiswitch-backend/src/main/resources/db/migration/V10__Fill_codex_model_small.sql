-- Fill model_name_small (secondary / lighter model) for Codex providers
-- where cc-switch lists a second model alongside the primary.
UPDATE model_provider
SET model_name_small = 'deepseek-v4-flash'
WHERE code = 'deepseek'
  AND target_tool = 'CODEX'
  AND model_name_small IS NULL;

UPDATE model_provider
SET model_name_small = 'qwen3-max'
WHERE code = 'qwen'
  AND target_tool = 'CODEX'
  AND model_name_small IS NULL;
