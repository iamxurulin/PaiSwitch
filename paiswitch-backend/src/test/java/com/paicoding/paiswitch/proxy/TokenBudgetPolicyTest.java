package com.paicoding.paiswitch.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenBudgetPolicyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldRaiseSkyclawMaxTokensToMinimum() {
        ObjectNode request = mapper.createObjectNode();
        request.put("max_tokens", 128);

        TokenBudgetPolicy.applyProviderMinimums("skyclaw", request);

        assertEquals(TokenBudgetPolicy.SKYCLAW_MIN_MAX_TOKENS, request.get("max_tokens").asInt());
    }

    @Test
    void shouldKeepLargerSkyclawMaxTokens() {
        ObjectNode request = mapper.createObjectNode();
        request.put("max_tokens", 8192);

        TokenBudgetPolicy.applyProviderMinimums("skyclaw", request);

        assertEquals(8192, request.get("max_tokens").asInt());
    }

    @Test
    void shouldNotChangeOtherProviders() {
        ObjectNode request = mapper.createObjectNode();
        request.put("max_tokens", 128);

        TokenBudgetPolicy.applyProviderMinimums("deepseek", request);

        assertEquals(128, request.get("max_tokens").asInt());
    }
}
