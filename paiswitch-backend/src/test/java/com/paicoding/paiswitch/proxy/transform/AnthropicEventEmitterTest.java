package com.paicoding.paiswitch.proxy.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicEventEmitterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicEventEmitter emitter = new AnthropicEventEmitter();

    @Test
    void textResponseEmitsUsageInputTokensExactlyOnce() throws Exception {
        JsonNode chat = mapper.readTree("""
                {
                  "id": "cmpl-abc",
                  "model": "skywork-ai/skyclaw-v1",
                  "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "Hello!"},
                    "finish_reason": "stop"
                  }],
                  "usage": {"prompt_tokens": 7, "completion_tokens": 3, "total_tokens": 10}
                }
                """);

        CapturingSseEmitter sse = new CapturingSseEmitter();
        emitter.emitFromChatCompletion(sse, chat, "skywork-ai/skyclaw-v1");

        List<String> events = sse.capturedEventNames;
        List<JsonNode> payloads = sse.capturedPayloads;

        assertEquals(List.of("message_start", "content_block_start", "content_block_delta",
                "content_block_stop", "message_delta", "message_stop"), events);

        JsonNode start = findPayload(payloads, "message_start");
        assertNotNull(start);
        JsonNode startMsg = start.get("message");
        // CRITICAL: usage.input_tokens must exist and be the prompt_tokens value.
        assertTrue(startMsg.get("usage").has("input_tokens"));
        assertEquals(7L, startMsg.get("usage").get("input_tokens").asLong());
        assertEquals(0L, startMsg.get("usage").get("output_tokens").asLong());
        assertEquals("skywork-ai/skyclaw-v1", startMsg.get("model").asText());

        JsonNode delta = findPayload(payloads, "content_block_delta");
        assertEquals("text_delta", delta.get("delta").get("type").asText());
        assertEquals("Hello!", delta.get("delta").get("text").asText());

        JsonNode msgDelta = findPayload(payloads, "message_delta");
        assertEquals("end_turn", msgDelta.get("delta").get("stop_reason").asText());
        assertEquals(3L, msgDelta.get("usage").get("output_tokens").asLong());
    }

    @Test
    void usageStillPresentWhenUpstreamOmitsIt() throws Exception {
        // Some providers don't include `usage`. Claude Code still requires
        // input_tokens / output_tokens or it crashes on `_.input_tokens`.
        JsonNode chat = mapper.readTree("""
                {
                  "id": "cmpl-no-usage",
                  "choices": [{
                    "message": {"role": "assistant", "content": "hi"},
                    "finish_reason": "stop"
                  }]
                }
                """);

        CapturingSseEmitter sse = new CapturingSseEmitter();
        emitter.emitFromChatCompletion(sse, chat, "x");

        JsonNode start = findPayload(sse.capturedPayloads, "message_start");
        assertEquals(0L, start.get("message").get("usage").get("input_tokens").asLong());
        JsonNode msgDelta = findPayload(sse.capturedPayloads, "message_delta");
        assertEquals(0L, msgDelta.get("usage").get("output_tokens").asLong());
    }

    @Test
    void toolCallsEmitToolUseContentBlocks() throws Exception {
        JsonNode chat = mapper.readTree("""
                {
                  "id": "cmpl-tool",
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": "Let me check.",
                      "tool_calls": [{
                        "id": "call_xyz",
                        "type": "function",
                        "function": {"name": "get_weather", "arguments": "{\\"city\\":\\"SF\\"}"}
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }],
                  "usage": {"prompt_tokens": 12, "completion_tokens": 18}
                }
                """);

        CapturingSseEmitter sse = new CapturingSseEmitter();
        emitter.emitFromChatCompletion(sse, chat, "x");

        // index 0 is the text block; index 1 is the tool_use block.
        JsonNode toolStart = findToolUseStart(sse.capturedPayloads);
        assertNotNull(toolStart);
        assertEquals(1, toolStart.get("index").asInt());
        assertEquals("tool_use", toolStart.get("content_block").get("type").asText());
        assertEquals("call_xyz", toolStart.get("content_block").get("id").asText());
        assertEquals("get_weather", toolStart.get("content_block").get("name").asText());

        JsonNode toolDelta = findToolUseDelta(sse.capturedPayloads);
        assertNotNull(toolDelta);
        assertEquals("input_json_delta", toolDelta.get("delta").get("type").asText());
        assertEquals("{\"city\":\"SF\"}", toolDelta.get("delta").get("partial_json").asText());

        JsonNode msgDelta = findPayload(sse.capturedPayloads, "message_delta");
        assertEquals("tool_use", msgDelta.get("delta").get("stop_reason").asText());
    }

    @Test
    void lengthFinishReasonMapsToMaxTokens() throws Exception {
        JsonNode chat = mapper.readTree("""
                {
                  "choices": [{
                    "message": {"role": "assistant", "content": "long..."},
                    "finish_reason": "length"
                  }],
                  "usage": {"prompt_tokens": 5, "completion_tokens": 100}
                }
                """);
        CapturingSseEmitter sse = new CapturingSseEmitter();
        emitter.emitFromChatCompletion(sse, chat, "x");
        JsonNode msgDelta = findPayload(sse.capturedPayloads, "message_delta");
        assertEquals("max_tokens", msgDelta.get("delta").get("stop_reason").asText());
    }

    @Test
    void errorEmitterFiresErrorEvent() {
        CapturingSseEmitter sse = new CapturingSseEmitter();
        emitter.emitError(sse, "boom", "upstream_error");
        JsonNode err = findPayload(sse.capturedPayloads, "error");
        assertNotNull(err);
        assertEquals("upstream_error", err.get("error").get("type").asText());
        assertEquals("boom", err.get("error").get("message").asText());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonNode findPayload(List<JsonNode> payloads, String anthropicType) {
        for (JsonNode p : payloads) {
            if (anthropicType.equals(p.path("type").asText())) {
                return p;
            }
        }
        return null;
    }

    private JsonNode findToolUseStart(List<JsonNode> payloads) {
        for (JsonNode p : payloads) {
            if ("content_block_start".equals(p.path("type").asText())
                    && "tool_use".equals(p.path("content_block").path("type").asText())) {
                return p;
            }
        }
        return null;
    }

    private JsonNode findToolUseDelta(List<JsonNode> payloads) {
        for (JsonNode p : payloads) {
            if ("content_block_delta".equals(p.path("type").asText())
                    && "input_json_delta".equals(p.path("delta").path("type").asText())) {
                return p;
            }
        }
        return null;
    }

    /**
     * SseEmitter subclass that captures the JsonNode payloads + event names instead
     * of writing to a real HTTP connection. Iterates the items the {@code event()}
     * builder produces and pulls out the {@link JsonNode} data + the {@code event:}
     * framing line.
     */
    private static class CapturingSseEmitter extends SseEmitter {
        final List<String> capturedEventNames = new ArrayList<>();
        final List<JsonNode> capturedPayloads = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            Set<DataWithMediaType> set = builder.build();
            for (DataWithMediaType d : set) {
                Object data = d.getData();
                if (data instanceof JsonNode jn) {
                    capturedPayloads.add(jn);
                } else if (data instanceof CharSequence s) {
                    String raw = s.toString();
                    int idx = raw.indexOf("event:");
                    if (idx >= 0) {
                        // Form is "event:<name>\n..." — stop at newline so we don't
                        // grab the trailing "data:" framing.
                        String after = raw.substring(idx + "event:".length());
                        int nl = after.indexOf('\n');
                        String name = (nl >= 0 ? after.substring(0, nl) : after).trim();
                        if (!name.isEmpty()) {
                            capturedEventNames.add(name);
                        }
                    }
                }
            }
        }

        @Override
        public void complete() {
            // no-op for tests
        }
    }
}
