-- Add SkyClaw-v1.0 as a Claude Code provider.
-- SkyClaw only exposes OpenAI chat-completions (apifree.ai/agent/v1), so
-- wire_api='openai' tells SettingsWriterService to route Claude Code through
-- the local /claude-proxy/{code} translator. The proxy maps Anthropic Messages
-- requests ↔ OpenAI chat-completions (in particular: prompt_tokens →
-- input_tokens so Claude Code's parser doesn't crash on '_.input_tokens').
INSERT INTO model_provider
    (code, name, description, base_url, model_name, model_name_small,
     is_builtin, is_active, sort_order, target_tool, wire_api)
SELECT
    'skyclaw',
    'SkyClaw v1.0',
    'Skywork SkyClaw agent model (auto-translated via local Claude proxy)',
    'https://api.apifree.ai/agent/v1',
    'skywork-ai/skyclaw-v1',
    'skywork-ai/skyclaw-v1-lite',
    TRUE,
    TRUE,
    5,
    'CLAUDE_CODE',
    'openai'
WHERE NOT EXISTS (
    SELECT 1 FROM model_provider
    WHERE code = 'skyclaw'
      AND target_tool = 'CLAUDE_CODE'
);
