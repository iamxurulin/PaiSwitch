-- Correct OpenAI Official default model: gpt-5-codex -> gpt-5.5
-- (gpt-5.5 matches Codex CLI's default before users customize it.)
UPDATE model_provider
SET model_name = 'gpt-5.5'
WHERE code = 'openai'
  AND target_tool = 'CODEX'
  AND model_name = 'gpt-5-codex';
