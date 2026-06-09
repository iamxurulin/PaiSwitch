package com.paicoding.paiswitch.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatResponseValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validChatCompletionReturnsNull() throws Exception {
        String body = """
                {
                  "id": "chatcmpl-1",
                  "choices": [
                    {"index": 0, "message": {"role": "assistant", "content": "hi"},
                     "finish_reason": "stop"}
                  ]
                }
                """;
        assertNull(ChatResponseValidator.describeBodyLevelError(mapper.readTree(body)));
    }

    @Test
    void apifreeAuthErrorWrappedInHttp200IsCaught() throws Exception {
        // The exact shape apifree.ai returned with HTTP 200 when we probed it.
        String body = """
                {"code":401,"error":{"code":"invalid_api_key",
                 "message":"Invalid API key","type":"authentication_error"},
                 "trace_id":"abc"}
                """;
        String msg = ChatResponseValidator.describeBodyLevelError(mapper.readTree(body));
        assertNotNull(msg);
        assertTrue(msg.contains("401"), msg);
        assertTrue(msg.contains("Invalid API key"), msg);
        assertTrue(msg.contains("authentication_error"), msg);
    }

    @Test
    void plainErrorObjectWithoutHttpInBodyIsStillCaught() throws Exception {
        String body = """
                {"error":{"message":"model not allowed","type":"invalid_request_error"}}
                """;
        String msg = ChatResponseValidator.describeBodyLevelError(mapper.readTree(body));
        assertNotNull(msg);
        assertTrue(msg.contains("model not allowed"), msg);
    }

    @Test
    void missingChoicesArrayIsCaught() throws Exception {
        String body = """
                {"id":"x","object":"chat.completion"}
                """;
        String msg = ChatResponseValidator.describeBodyLevelError(mapper.readTree(body));
        assertNotNull(msg);
        assertTrue(msg.contains("choices"), msg);
    }

    @Test
    void emptyChoicesArrayIsCaught() throws Exception {
        String body = """
                {"choices":[]}
                """;
        String msg = ChatResponseValidator.describeBodyLevelError(mapper.readTree(body));
        assertNotNull(msg);
        assertTrue(msg.contains("choices"), msg);
    }

    @Test
    void emptyAssistantOutputIsCaught() throws Exception {
        String body = """
                {
                  "choices": [
                    {"message": {"role": "assistant", "content": "", "tool_calls": []}}
                  ]
                }
                """;
        String msg = ChatResponseValidator.describeBodyLevelError(mapper.readTree(body));
        assertNotNull(msg);
        assertTrue(msg.contains("no assistant text"), msg);
    }

    @Test
    void reasoningContentCountsAsUsableOutput() throws Exception {
        String body = """
                {
                  "choices": [
                    {"message": {"role": "assistant", "content": "", "reasoning_content": "thinking"}}
                  ]
                }
                """;
        assertNull(ChatResponseValidator.describeBodyLevelError(mapper.readTree(body)));
    }

    @Test
    void nullBodyIsReported() {
        String msg = ChatResponseValidator.describeBodyLevelError(null);
        assertNotNull(msg);
        assertTrue(msg.toLowerCase().contains("empty"));
    }
}
