-- Add iFlytek Spark MaaS as Claude Code and Codex providers.
-- MaaS inference exposes OpenAI-compatible chat-completions:
-- https://maas-api.cn-huabei-1.xf-yun.com/v2 for services published on or after 2026-01-10.
-- If a MaaS service requires resourceId, append it as ?lora_id=<resourceId>;
-- PaiSwitch strips the query from the upstream URL and forwards lora_id as a header.
INSERT INTO model_provider
    (code, name, description, base_url, model_name, model_name_small,
     is_builtin, is_active, sort_order, target_tool, wire_api)
SELECT
    'xfyun-maas',
    'iFlytek Spark MaaS',
    '讯飞星辰 MaaS OpenAI-compatible inference via local Claude proxy',
    'https://maas-api.cn-huabei-1.xf-yun.com/v2',
    '2615338780587020',
    NULL,
    TRUE,
    TRUE,
    6,
    'CLAUDE_CODE',
    'openai'
WHERE NOT EXISTS (
    SELECT 1 FROM model_provider
    WHERE code = 'xfyun-maas'
      AND target_tool = 'CLAUDE_CODE'
);

INSERT INTO model_provider
    (code, name, description, base_url, model_name, model_name_small,
     is_builtin, is_active, sort_order, target_tool, wire_api, provider_key)
SELECT
    'xfyun-maas',
    'iFlytek Spark MaaS',
    '讯飞星辰 MaaS OpenAI-compatible chat-completions',
    'https://maas-api.cn-huabei-1.xf-yun.com/v2',
    '2615338780587020',
    NULL,
    TRUE,
    TRUE,
    7,
    'CODEX',
    'chat',
    'xfyun_maas'
WHERE NOT EXISTS (
    SELECT 1 FROM model_provider
    WHERE code = 'xfyun-maas'
      AND target_tool = 'CODEX'
);
