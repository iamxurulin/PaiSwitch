package com.paicoding.paiswitch.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XfyunRequestAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void stripsToolProtocolForModelsThatDoNotSupportTools() throws Exception {
        ObjectNode request = (ObjectNode) mapper.readTree("""
                {
                  "model": "xopqwen36v35b",
                  "messages": [
                    {"role": "assistant", "content": "", "tool_calls": [
                      {"id": "toolu_01", "type": "function",
                       "function": {"name": "Read", "arguments": "{\\"file_path\\":\\"README.md\\"}"}}
                    ]},
                    {"role": "tool", "tool_call_id": "toolu_01", "content": "file text"},
                    {"role": "user", "content": "continue"}
                  ],
                  "tools": [
                    {"type": "function", "function": {
                      "name": "Read",
                      "description": "Read a file",
                      "parameters": {"type": "object"}
                    }}
                  ],
                  "tool_choice": "auto"
                }
                """);

        XfyunRequestAdapter.AdaptedRequest adapted = XfyunRequestAdapter.adapt("xfyun-maas", request);

        assertTrue(adapted.toolsStripped());
        assertFalse(request.has("tools"));
        assertFalse(request.has("tool_choice"));
        JsonNode messages = request.get("messages");
        assertFalse(messages.get(0).has("tool_calls"));
        assertTrue(messages.get(0).path("content").asText().contains("[tool_call toolu_01 Read]"));
        assertEquals("user", messages.get(1).path("role").asText());
        assertFalse(messages.get(1).has("tool_call_id"));
        assertTrue(messages.get(1).path("content").asText().contains("[tool_result toolu_01]"));
    }

    @Test
    void sanitizesToolNamesForXfyunToolCapableModelsAndRestoresResponseNames() throws Exception {
        ObjectNode request = (ObjectNode) mapper.readTree("""
                {
                  "model": "xopdeepseekv32",
                  "messages": [
                    {"role": "user", "content": "go"}
                  ],
                  "tools": [
                    {"type": "function", "function": {
                      "name": "mcp__chrome-devtools__take_snapshot",
                      "description": "Take snapshot",
                      "parameters": {"type": "object"}
                    }}
                  ],
                  "tool_choice": {
                    "type": "function",
                    "function": {"name": "mcp__chrome-devtools__take_snapshot"}
                  }
                }
                """);

        XfyunRequestAdapter.AdaptedRequest adapted = XfyunRequestAdapter.adapt("xfyun-maas", request);

        assertFalse(adapted.toolsStripped());
        String sanitized = request.path("tools").get(0).path("function").path("name").asText();
        assertTrue(sanitized.matches("[A-Za-z0-9_]{1,32}"), sanitized);
        assertEquals("auto", request.path("tool_choice").asText());

        JsonNode restored = adapted.restoreResponseToolNames(mapper.readTree("""
                {
                  "choices": [
                    {"message": {"tool_calls": [
                      {"type": "function", "function": {"name": "%s", "arguments": "{}"}}
                    ]}}
                  ]
                }
                """.formatted(sanitized)));

        assertEquals("mcp__chrome-devtools__take_snapshot",
                restored.path("choices").get(0).path("message").path("tool_calls").get(0)
                        .path("function").path("name").asText());
    }
}
