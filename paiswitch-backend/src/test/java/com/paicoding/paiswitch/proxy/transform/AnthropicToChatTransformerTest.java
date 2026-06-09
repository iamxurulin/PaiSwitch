package com.paicoding.paiswitch.proxy.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicToChatTransformerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicToChatTransformer transformer = new AnthropicToChatTransformer();

    @Test
    void systemStringIsPrependedAsSystemMessage() throws Exception {
        JsonNode out = transformer.anthropicToChat(mapper.readTree("""
                {
                  "model": "skywork-ai/skyclaw-v1",
                  "max_tokens": 100,
                  "system": "You are helpful.",
                  "messages": [
                    {"role": "user", "content": "Hi"}
                  ]
                }
                """));

        JsonNode messages = out.get("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("You are helpful.", messages.get(0).get("content").asText());
        assertEquals("user", messages.get(1).get("role").asText());
        assertEquals("Hi", messages.get(1).get("content").asText());
        assertEquals(100, out.get("max_tokens").asInt());
    }

    @Test
    void systemContentBlocksAreJoined() throws Exception {
        JsonNode out = transformer.anthropicToChat(mapper.readTree("""
                {
                  "model": "x",
                  "system": [
                    {"type": "text", "text": "part one"},
                    {"type": "text", "text": "part two"}
                  ],
                  "messages": [
                    {"role": "user", "content": "Hi"}
                  ]
                }
                """));

        assertEquals("part one\npart two", out.get("messages").get(0).get("content").asText());
    }

    @Test
    void systemMessagesInsideAnthropicMessagesBecomeUserMessages() throws Exception {
        JsonNode out = transformer.anthropicToChat(mapper.readTree("""
                {
                  "system": "leading system",
                  "messages": [
                    {"role": "user", "content": "Hi"},
                    {"role": "system", "content": "system reminder"}
                  ]
                }
                """));

        JsonNode messages = out.get("messages");
        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("user", messages.get(1).get("role").asText());
        assertEquals("user", messages.get(2).get("role").asText());
        assertEquals("system reminder", messages.get(2).get("content").asText());
    }

    @Test
    void assistantToolUseBecomesToolCalls() throws Exception {
        JsonNode out = transformer.anthropicToChat(mapper.readTree("""
                {
                  "model": "x",
                  "messages": [
                    {"role": "user", "content": "What's the weather?"},
                    {"role": "assistant", "content": [
                      {"type": "text", "text": "Let me check."},
                      {"type": "tool_use", "id": "toolu_01", "name": "get_weather",
                       "input": {"location": "SF"}}
                    ]}
                  ]
                }
                """));

        JsonNode assistant = out.get("messages").get(1);
        assertEquals("assistant", assistant.get("role").asText());
        assertEquals("Let me check.", assistant.get("content").asText());
        JsonNode toolCalls = assistant.get("tool_calls");
        assertNotNull(toolCalls);
        assertEquals(1, toolCalls.size());
        assertEquals("toolu_01", toolCalls.get(0).get("id").asText());
        assertEquals("function", toolCalls.get(0).get("type").asText());
        assertEquals("get_weather", toolCalls.get(0).get("function").get("name").asText());
        // Arguments must be a serialized JSON string, not a JSON object.
        String args = toolCalls.get(0).get("function").get("arguments").asText();
        JsonNode parsedArgs = mapper.readTree(args);
        assertEquals("SF", parsedArgs.get("location").asText());
    }

    @Test
    void userToolResultBecomesToolMessage() throws Exception {
        JsonNode out = transformer.anthropicToChat(mapper.readTree("""
                {
                  "model": "x",
                  "messages": [
                    {"role": "user", "content": [
                      {"type": "tool_result", "tool_use_id": "toolu_01",
                       "content": "It's sunny."}
                    ]}
                  ]
                }
                """));

        JsonNode messages = out.get("messages");
        assertEquals(1, messages.size());
        JsonNode tool = messages.get(0);
        assertEquals("tool", tool.get("role").asText());
        assertEquals("toolu_01", tool.get("tool_call_id").asText());
        assertEquals("It's sunny.", tool.get("content").asText());
    }

    @Test
    void userToolResultWithContentBlocksIsFlattened() throws Exception {
        JsonNode out = transformer.anthropicToChat(mapper.readTree("""
                {
                  "model": "x",
                  "messages": [
                    {"role": "user", "content": [
                      {"type": "tool_result", "tool_use_id": "toolu_02",
                       "content": [
                         {"type": "text", "text": "line A"},
                         {"type": "text", "text": "line B"}
                       ]}
                    ]}
                  ]
                }
                """));

        assertEquals("line A\nline B", out.get("messages").get(0).get("content").asText());
    }

    @Test
    void toolsInputSchemaIsRenamedToParameters() throws Exception {
        JsonNode out = transformer.anthropicToChat(mapper.readTree("""
                {
                  "model": "x",
                  "messages": [{"role": "user", "content": "go"}],
                  "tools": [{
                    "name": "search",
                    "description": "search the web",
                    "input_schema": {"type": "object", "properties": {"q": {"type": "string"}}}
                  }]
                }
                """));

        JsonNode tool = out.get("tools").get(0);
        assertEquals("function", tool.get("type").asText());
        JsonNode fn = tool.get("function");
        assertEquals("search", fn.get("name").asText());
        assertEquals("search the web", fn.get("description").asText());
        assertEquals("object", fn.get("parameters").get("type").asText());
        assertFalse(fn.has("input_schema"));
    }

    @Test
    void toolChoiceAnyMapsToRequired() throws Exception {
        JsonNode out = transformer.anthropicToChat(mapper.readTree("""
                {
                  "messages": [{"role": "user", "content": "go"}],
                  "tool_choice": {"type": "any"}
                }
                """));
        assertEquals("required", out.get("tool_choice").asText());
    }

    @Test
    void toolChoiceSpecificToolMapsToFunctionObject() throws Exception {
        JsonNode out = transformer.anthropicToChat(mapper.readTree("""
                {
                  "messages": [{"role": "user", "content": "go"}],
                  "tool_choice": {"type": "tool", "name": "search"}
                }
                """));
        JsonNode tc = out.get("tool_choice");
        assertEquals("function", tc.get("type").asText());
        assertEquals("search", tc.get("function").get("name").asText());
    }

    @Test
    void stopSequencesRenamedToStop() throws Exception {
        JsonNode out = transformer.anthropicToChat(mapper.readTree("""
                {
                  "messages": [{"role": "user", "content": "x"}],
                  "stop_sequences": ["END"]
                }
                """));
        assertTrue(out.has("stop"));
        assertEquals("END", out.get("stop").get(0).asText());
    }

    @Test
    void topKIsNotForwardedToOpenAiCompatibleUpstreams() throws Exception {
        JsonNode out = transformer.anthropicToChat(mapper.readTree("""
                {
                  "messages": [{"role": "user", "content": "x"}],
                  "temperature": 0.3,
                  "top_k": 250
                }
                """));
        assertEquals(0.3, out.get("temperature").asDouble());
        assertFalse(out.has("top_k"));
    }

    @Test
    void imageBlockIsReplacedWithPlaceholder() throws Exception {
        JsonNode out = transformer.anthropicToChat(mapper.readTree("""
                {
                  "messages": [{"role": "user", "content": [
                    {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "..."}},
                    {"type": "text", "text": "describe this"}
                  ]}]
                }
                """));
        String content = out.get("messages").get(0).get("content").asText();
        assertTrue(content.contains("[image omitted by proxy]"));
        assertTrue(content.contains("describe this"));
    }
}
