package com.paicoding.paiswitch.proxy.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

/**
 * Emits the Anthropic Messages-API SSE event sequence that Claude Code expects,
 * synthesized from a single (non-streaming) OpenAI chat-completions JSON
 * response.
 * <p>
 * Event sequence for a text reply:
 * <pre>
 *   message_start          (usage.input_tokens populated)
 *   content_block_start    (index=0, type=text)
 *   content_block_delta    (text_delta, full text)
 *   content_block_stop     (index=0)
 *   message_delta          (stop_reason + usage.output_tokens)
 *   message_stop
 * </pre>
 * Tool calls add a parallel sequence per call (index increments, type=tool_use,
 * input_json_delta carries the full JSON arguments string).
 * <p>
 * <b>Why this exists:</b> Claude Code parses {@code usage.input_tokens} and
 * {@code usage.output_tokens} from {@code message_start} / {@code message_delta}.
 * If those keys are missing it crashes with
 * {@code undefined is not an object (evaluating '_.input_tokens')}. We always
 * write them — falling back to 0 if upstream omits them.
 */
@Slf4j
@Component
public class AnthropicEventEmitter {

    private final ObjectMapper mapper = new ObjectMapper();

    public void emitFromChatCompletion(SseEmitter emitter, JsonNode chatBody, String requestedModel) throws IOException {
        String messageId = "msg_" + chatBody.path("id").asText(UUID.randomUUID().toString());
        String model = !chatBody.path("model").asText("").isEmpty()
                ? chatBody.path("model").asText()
                : requestedModel;

        JsonNode choice = chatBody.path("choices").path(0);
        JsonNode message = choice.path("message");
        String finishReason = choice.path("finish_reason").asText("stop");

        String text = extractText(message);
        JsonNode toolCalls = message.get("tool_calls");
        JsonNode usage = chatBody.get("usage");

        long inputTokens = usage == null ? 0 : usage.path("prompt_tokens").asLong(0);
        long outputTokens = usage == null ? 0 : usage.path("completion_tokens").asLong(0);

        // 1. message_start — usage.input_tokens MUST be present; output_tokens
        //    is conventionally 0 here and the real number arrives in message_delta.
        ObjectNode startUsage = mapper.createObjectNode();
        startUsage.put("input_tokens", inputTokens);
        startUsage.put("output_tokens", 0L);
        ObjectNode startMessage = mapper.createObjectNode();
        startMessage.put("id", messageId);
        startMessage.put("type", "message");
        startMessage.put("role", "assistant");
        startMessage.set("content", mapper.createArrayNode());
        startMessage.put("model", model);
        startMessage.putNull("stop_reason");
        startMessage.putNull("stop_sequence");
        startMessage.set("usage", startUsage);
        ObjectNode messageStart = mapper.createObjectNode();
        messageStart.put("type", "message_start");
        messageStart.set("message", startMessage);
        sendEvent(emitter, "message_start", messageStart);

        int blockIndex = 0;

        // 2. text block (if any).
        if (text != null && !text.isEmpty()) {
            emitTextBlock(emitter, blockIndex, text);
            blockIndex++;
        }

        // 3. tool_use blocks (if any).
        if (toolCalls != null && toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                emitToolUseBlock(emitter, blockIndex, call);
                blockIndex++;
            }
        }

        // 4. message_delta — final stop_reason + output_tokens.
        String stopReason = mapFinishReason(finishReason,
                toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty());
        ObjectNode delta = mapper.createObjectNode();
        delta.put("stop_reason", stopReason);
        delta.putNull("stop_sequence");
        ObjectNode endUsage = mapper.createObjectNode();
        endUsage.put("output_tokens", outputTokens);
        ObjectNode messageDelta = mapper.createObjectNode();
        messageDelta.put("type", "message_delta");
        messageDelta.set("delta", delta);
        messageDelta.set("usage", endUsage);
        sendEvent(emitter, "message_delta", messageDelta);

        // 5. message_stop.
        ObjectNode messageStop = mapper.createObjectNode();
        messageStop.put("type", "message_stop");
        sendEvent(emitter, "message_stop", messageStop);
    }

    /**
     * Emit an Anthropic error event sequence and close the emitter. Used when
     * the proxy can't reach upstream or the upstream returned non-2xx.
     */
    public void emitError(SseEmitter emitter, String message, String type) {
        try {
            ObjectNode err = mapper.createObjectNode();
            err.put("type", "error");
            ObjectNode errBody = mapper.createObjectNode();
            errBody.put("type", type == null ? "api_error" : type);
            errBody.put("message", message == null ? "Unknown error" : message);
            err.set("error", errBody);
            sendEvent(emitter, "error", err);
        } catch (Exception e) {
            log.warn("Failed to emit error event: {}", e.getMessage());
        } finally {
            emitter.complete();
        }
    }

    // ── content blocks ───────────────────────────────────────────────────────

    private void emitTextBlock(SseEmitter emitter, int index, String text) throws IOException {
        // content_block_start: empty text block.
        ObjectNode startBlock = mapper.createObjectNode();
        startBlock.put("type", "text");
        startBlock.put("text", "");
        ObjectNode start = mapper.createObjectNode();
        start.put("type", "content_block_start");
        start.put("index", index);
        start.set("content_block", startBlock);
        sendEvent(emitter, "content_block_start", start);

        // content_block_delta: full text as one delta (upstream was non-streaming).
        ObjectNode textDelta = mapper.createObjectNode();
        textDelta.put("type", "text_delta");
        textDelta.put("text", text);
        ObjectNode deltaEv = mapper.createObjectNode();
        deltaEv.put("type", "content_block_delta");
        deltaEv.put("index", index);
        deltaEv.set("delta", textDelta);
        sendEvent(emitter, "content_block_delta", deltaEv);

        // content_block_stop.
        ObjectNode stop = mapper.createObjectNode();
        stop.put("type", "content_block_stop");
        stop.put("index", index);
        sendEvent(emitter, "content_block_stop", stop);
    }

    private void emitToolUseBlock(SseEmitter emitter, int index, JsonNode openAiCall) throws IOException {
        String callId = openAiCall.path("id").asText("toolu_" + UUID.randomUUID());
        String name = openAiCall.path("function").path("name").asText("");
        String args = openAiCall.path("function").path("arguments").asText("");

        ObjectNode startBlock = mapper.createObjectNode();
        startBlock.put("type", "tool_use");
        startBlock.put("id", callId);
        startBlock.put("name", name);
        startBlock.set("input", mapper.createObjectNode());
        ObjectNode start = mapper.createObjectNode();
        start.put("type", "content_block_start");
        start.put("index", index);
        start.set("content_block", startBlock);
        sendEvent(emitter, "content_block_start", start);

        // Anthropic expects input_json_delta with the partial JSON string. We send
        // the whole serialized arguments as one delta. If empty, send `{}` so
        // Claude Code parses to an empty object rather than treating it as null.
        String jsonArgs = (args == null || args.isEmpty()) ? "{}" : args;
        ObjectNode toolDelta = mapper.createObjectNode();
        toolDelta.put("type", "input_json_delta");
        toolDelta.put("partial_json", jsonArgs);
        ObjectNode deltaEv = mapper.createObjectNode();
        deltaEv.put("type", "content_block_delta");
        deltaEv.put("index", index);
        deltaEv.set("delta", toolDelta);
        sendEvent(emitter, "content_block_delta", deltaEv);

        ObjectNode stop = mapper.createObjectNode();
        stop.put("type", "content_block_stop");
        stop.put("index", index);
        sendEvent(emitter, "content_block_stop", stop);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String extractText(JsonNode message) {
        if (message == null || message.isNull()) return "";
        JsonNode content = message.get("content");
        if (content == null || content.isNull()) return message.path("reasoning_content").asText("");
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.isObject() && part.path("type").asText("").endsWith("text") && part.has("text")) {
                    sb.append(part.path("text").asText(""));
                }
            }
            return !sb.isEmpty() ? sb.toString() : message.path("reasoning_content").asText("");
        }
        return message.path("reasoning_content").asText("");
    }

    /**
     * Maps OpenAI {@code finish_reason} to Anthropic {@code stop_reason}.
     * <ul>
     *   <li>{@code stop} → {@code end_turn}</li>
     *   <li>{@code length} → {@code max_tokens}</li>
     *   <li>{@code tool_calls} / {@code function_call} → {@code tool_use}</li>
     *   <li>otherwise → {@code end_turn} (default)</li>
     * </ul>
     * If upstream reports {@code stop} but emitted tool_calls anyway (some
     * providers do), force {@code tool_use} so Claude Code knows to execute.
     */
    private String mapFinishReason(String finishReason, boolean hasToolCalls) {
        if (hasToolCalls) return "tool_use";
        if (finishReason == null) return "end_turn";
        return switch (finishReason) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "tool_calls", "function_call" -> "tool_use";
            case "content_filter" -> "stop_sequence";
            default -> "end_turn";
        };
    }

    // Use SSE's "data: <json>\n\n" plain framing without an event name; Anthropic's
    // own server emits both `event: <name>` and `data: <json>`, and Claude Code
    // tolerates both. SseEmitter#event lets us include the name to match.
    private void sendEvent(SseEmitter emitter, String eventName, JsonNode data) throws IOException {
        emitter.send(SseEmitter.event()
                .name(eventName)
                .data(data, MediaType.APPLICATION_JSON));
    }
}
