-- Align Codex built-in provider models with cc-switch's current presets.
-- Source: cc-switch/src/config/codexProviderPresets.ts
-- DeepSeek uses v4-pro (per project decision; cc-switch defaults to v4-flash but
-- exposes both — we pick the stronger one as the single default).
UPDATE model_provider
SET model_name = 'deepseek-v4-pro'
WHERE code = 'deepseek'
  AND target_tool = 'CODEX'
  AND model_name = 'deepseek-chat';

UPDATE model_provider
SET model_name = 'glm-5'
WHERE code = 'zhipu'
  AND target_tool = 'CODEX'
  AND model_name = 'glm-4.5';

UPDATE model_provider
SET model_name = 'kimi-k2.6'
WHERE code = 'kimi'
  AND target_tool = 'CODEX'
  AND model_name = 'kimi-k2-0905-preview';
