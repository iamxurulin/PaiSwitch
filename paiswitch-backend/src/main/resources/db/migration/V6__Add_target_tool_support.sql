-- Multi-tool support: each provider belongs to either Claude Code or Codex.
-- target_tool: CLAUDE_CODE | CODEX
-- wire_api / provider_key are Codex-specific TOML metadata (NULL for Claude providers).
ALTER TABLE model_provider
    ADD COLUMN target_tool VARCHAR(20) NOT NULL DEFAULT 'CLAUDE_CODE',
    ADD COLUMN wire_api VARCHAR(20) NULL,
    ADD COLUMN provider_key VARCHAR(50) NULL;

ALTER TABLE model_provider DROP INDEX code;
ALTER TABLE model_provider ADD UNIQUE KEY uk_code_tool (code, target_tool);
ALTER TABLE model_provider ADD INDEX idx_target_tool (target_tool);

-- Allow each user to keep a current provider per tool.
-- Existing current_provider_id is reused for Claude; current_codex_provider_id is new and nullable.
ALTER TABLE user_config
    ADD COLUMN current_codex_provider_id BIGINT NULL,
    ADD CONSTRAINT fk_user_config_codex_provider
        FOREIGN KEY (current_codex_provider_id) REFERENCES model_provider(id);

-- Allow API key to coexist for same provider code across tools by relaxing uniqueness if needed.
-- (provider_id already distinguishes rows; nothing to change in api_key.)
