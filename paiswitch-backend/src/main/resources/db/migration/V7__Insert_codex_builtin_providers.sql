-- Insert Codex built-in providers.
-- provider_key is the TOML [model_providers.<key>] identifier written to ~/.codex/config.toml.
-- wire_api: "responses" for OpenAI-native Responses API, "openai_chat" for chat-completions style.
INSERT INTO model_provider
    (code, name, description, base_url, model_name, model_name_small,
     is_builtin, is_active, sort_order, target_tool, wire_api, provider_key)
VALUES
    ('openai',   'OpenAI Official', 'OpenAI Codex official endpoint',
     'https://api.openai.com/v1',                          'gpt-5-codex', NULL,
     TRUE, TRUE, 1, 'CODEX', 'responses',   'openai'),

    ('deepseek', 'DeepSeek',         'DeepSeek API via OpenAI chat-completions',
     'https://api.deepseek.com',                           'deepseek-chat', NULL,
     TRUE, TRUE, 2, 'CODEX', 'openai_chat', 'deepseek'),

    ('zhipu',    'Zhipu GLM',        'Zhipu GLM via OpenAI chat-completions',
     'https://open.bigmodel.cn/api/paas/v4',               'glm-4.5', NULL,
     TRUE, TRUE, 3, 'CODEX', 'openai_chat', 'zhipu_glm'),

    ('kimi',     'Kimi (Moonshot)',  'Moonshot Kimi via OpenAI chat-completions',
     'https://api.moonshot.cn/v1',                         'kimi-k2-0905-preview', NULL,
     TRUE, TRUE, 4, 'CODEX', 'openai_chat', 'kimi'),

    ('qwen',     'Qwen (Bailian)',   'Aliyun Bailian Qwen-Coder via OpenAI chat-completions',
     'https://dashscope.aliyuncs.com/compatible-mode/v1',  'qwen3-coder-plus', NULL,
     TRUE, TRUE, 5, 'CODEX', 'openai_chat', 'bailian');
