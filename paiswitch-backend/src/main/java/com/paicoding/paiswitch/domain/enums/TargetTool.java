package com.paicoding.paiswitch.domain.enums;

public enum TargetTool {
    CLAUDE_CODE,
    CODEX;

    public static TargetTool fromQueryParam(String value) {
        if (value == null || value.isBlank()) {
            return CLAUDE_CODE;
        }
        String normalized = value.trim().toUpperCase().replace('-', '_');
        try {
            return TargetTool.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return CLAUDE_CODE;
        }
    }
}
