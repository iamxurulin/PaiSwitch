-- Codex CLI only accepts `wire_api = "chat"` or `"responses"`. The value
-- `"openai_chat"` originally seeded in V7 is rejected by recent Codex versions
-- with: "unknown variant `openai_chat`, expected `responses`". Normalize all
-- chat-completions providers to the canonical `"chat"` value.
UPDATE model_provider
SET wire_api = 'chat'
WHERE target_tool = 'CODEX'
  AND wire_api = 'openai_chat';
