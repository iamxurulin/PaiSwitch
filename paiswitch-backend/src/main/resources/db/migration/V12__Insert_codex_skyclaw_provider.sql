-- Add SkyClaw-v1.0 as a Codex provider.
-- SkyClaw is OpenAI chat-completions compatible via apifree.ai.
INSERT INTO model_provider
    (code, name, description, base_url, model_name, model_name_small,
     is_builtin, is_active, sort_order, target_tool, wire_api, provider_key)
SELECT
    'skyclaw',
    'SkyClaw v1.0',
    'Skywork SkyClaw agent model via OpenAI-compatible chat-completions',
    'https://api.apifree.ai/v1',
    'skywork-ai/skyclaw-v1',
    'skywork-ai/skyclaw-v1-lite',
    TRUE,
    TRUE,
    6,
    'CODEX',
    'chat',
    'skyclaw'
WHERE NOT EXISTS (
    SELECT 1 FROM model_provider
    WHERE code = 'skyclaw'
      AND target_tool = 'CODEX'
);
