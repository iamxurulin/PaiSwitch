package com.paicoding.paiswitch.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class TokenBudgetPolicy {

    static final int SKYCLAW_MIN_MAX_TOKENS = 4096;

    private TokenBudgetPolicy() {
    }

    static void applyProviderMinimums(String providerCode, ObjectNode chatRequest) {
        if (!"skyclaw".equalsIgnoreCase(providerCode)) {
            return;
        }

        JsonNode maxTokens = chatRequest.get("max_tokens");
        if (maxTokens == null || !maxTokens.canConvertToInt() || maxTokens.asInt() < SKYCLAW_MIN_MAX_TOKENS) {
            chatRequest.put("max_tokens", SKYCLAW_MIN_MAX_TOKENS);
        }
    }
}
