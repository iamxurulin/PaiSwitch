package com.paicoding.paiswitch.proxy.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * Emits the OpenAI Responses-API SSE event sequence that Codex CLI expects,
 * synthesized from a single (non-streaming) chat-completions JSON response.
 * <p>
 * Event sequence for a text reply:
 * <pre>
 *   response.created            → response in_progress, empty output
 *   response.in_progress
 *   [if reasoning present]
 *     response.output_item.added             type=reasoning, status=in_progress
 *     response.reasoning_summary_part.added
 *     response.reasoning_summary_text.delta  (one delta with full text)
 *     response.reasoning_summary_text.done
 *     response.reasoning_summary_part.done
 *     response.output_item.done              type=reasoning, status=completed
 *   response.output_item.added               type=message, status=in_progress
 *   response.content_part.added              part=output_text
 *   response.output_text.delta               (one delta with full text)
 *   response.output_text.done
 *   response.content_part.done
 *   response.output_item.done                type=message, status=completed
 *   response.completed                       full response object with output[] + usage
 * </pre>
 * Tool calls produce a parallel sequence of function_call output items.
 */
@Slf4j
@Component
public class ResponseEventEmitter {

    private final ObjectMapper mapper = new ObjectMapper();

    public void emitFromChatCompletion(SseEmitter emitter, JsonNode chatBody, String requestedModel) throws IOException {
        String responseId = "resp_" + chatBody.path("id").asText(UUID.randomUUID().toString());
        long createdAt = chatBody.path("created").asLong(Instant.now().getEpochSecond());
        String model = !chatBody.path("model").asText("").isEmpty()
                ? chatBody.path("model").asText()
                : requestedModel;

        JsonNode choice = chatBody.path("choices").path(0);
        JsonNode message = choice.path("message");
        String finishReason = choice.path("finish_reason").asText("stop");

        String reasoning = extractReasoning(message);
        String text = extractText(message);
        JsonNode toolCalls = message.get("tool_calls");

        // 1. response.created + 2. response.in_progress
        sendEvent(emitter, "response.created",
                wrapResponse(responseId, "in_progress", model, createdAt, mapper.createArrayNode(), null));
        sendEvent(emitter, "response.in_progress",
                wrapResponse(responseId, "in_progress", model, createdAt, mapper.createArrayNode(), null));

        int outputIndex = 0;
        ArrayNode finalOutput = mapper.createArrayNode();

        // Reasoning block (if any).
        if (reasoning != null && !reasoning.isBlank()) {
            String reasoningItemId = "rs_" + responseId;
            emitReasoningItem(emitter, reasoningItemId, outputIndex, reasoning);
            finalOutput.add(buildReasoningItem(reasoningItemId, reasoning, "completed"));
            outputIndex++;
        }

        // Text message block (if any).
        if (text != null && !text.isEmpty()) {
            String msgItemId = responseId + "_msg";
            emitMessageItem(emitter, msgItemId, outputIndex, text);
            finalOutput.add(buildMessageItem(msgItemId, text, "completed"));
            outputIndex++;
        }

        // Tool call blocks (if any).
        if (toolCalls != null && toolCalls.isArray()) {
            for (JsonNode toolCall : toolCalls) {
                String callId = toolCall.path("id").asText("call_" + UUID.randomUUID());
                String name = toolCall.path("function").path("name").asText("");
                String args = toolCall.path("function").path("arguments").asText("");
                String fcItemId = "fc_" + callId;
                emitFunctionCallItem(emitter, fcItemId, outputIndex, callId, name, args);
                finalOutput.add(buildFunctionCallItem(fcItemId, callId, name, args, "completed"));
                outputIndex++;
            }
        }

        // Final completed event.
        String status = "length".equals(finishReason) ? "incomplete" : "completed";
        ObjectNode finalResponse = wrapResponse(responseId, status, model, createdAt, finalOutput,
                chatBody.get("usage"));
        if ("incomplete".equals(status)) {
            ObjectNode incomplete = mapper.createObjectNode();
            incomplete.put("reason", "max_output_tokens");
            finalResponse.set("incomplete_details", incomplete);
        }
        sendEvent(emitter, "response." + status, finalResponse);
    }

    // ── builders for streaming events ─────────────────────────────────────────

    private void emitReasoningItem(SseEmitter emitter, String itemId, int outputIndex, String reasoning) throws IOException {
        // added (in_progress, empty summary)
        ObjectNode added = mapper.createObjectNode();
        added.put("type", "response.output_item.added");
        added.put("output_index", outputIndex);
        added.set("item", buildReasoningItem(itemId, "", "in_progress"));
        sendEvent(emitter, "response.output_item.added", added);

        ObjectNode partAdded = mapper.createObjectNode();
        partAdded.put("type", "response.reasoning_summary_part.added");
        partAdded.put("item_id", itemId);
        partAdded.put("output_index", outputIndex);
        partAdded.put("summary_index", 0);
        ObjectNode partEmpty = mapper.createObjectNode();
        partEmpty.put("type", "summary_text");
        partEmpty.put("text", "");
        partAdded.set("part", partEmpty);
        sendEvent(emitter, "response.reasoning_summary_part.added", partAdded);

        // single delta with full text
        ObjectNode delta = mapper.createObjectNode();
        delta.put("type", "response.reasoning_summary_text.delta");
        delta.put("item_id", itemId);
        delta.put("output_index", outputIndex);
        delta.put("summary_index", 0);
        delta.put("delta", reasoning);
        sendEvent(emitter, "response.reasoning_summary_text.delta", delta);

        ObjectNode doneText = mapper.createObjectNode();
        doneText.put("type", "response.reasoning_summary_text.done");
        doneText.put("item_id", itemId);
        doneText.put("output_index", outputIndex);
        doneText.put("summary_index", 0);
        doneText.put("text", reasoning);
        sendEvent(emitter, "response.reasoning_summary_text.done", doneText);

        ObjectNode partDone = mapper.createObjectNode();
        partDone.put("type", "response.reasoning_summary_part.done");
        partDone.put("item_id", itemId);
        partDone.put("output_index", outputIndex);
        partDone.put("summary_index", 0);
        ObjectNode partFull = mapper.createObjectNode();
        partFull.put("type", "summary_text");
        partFull.put("text", reasoning);
        partDone.set("part", partFull);
        sendEvent(emitter, "response.reasoning_summary_part.done", partDone);

        ObjectNode itemDone = mapper.createObjectNode();
        itemDone.put("type", "response.output_item.done");
        itemDone.put("output_index", outputIndex);
        itemDone.set("item", buildReasoningItem(itemId, reasoning, "completed"));
        sendEvent(emitter, "response.output_item.done", itemDone);
    }

    private void emitMessageItem(SseEmitter emitter, String itemId, int outputIndex, String text) throws IOException {
        ObjectNode added = mapper.createObjectNode();
        added.put("type", "response.output_item.added");
        added.put("output_index", outputIndex);
        added.set("item", buildMessageItem(itemId, "", "in_progress"));
        sendEvent(emitter, "response.output_item.added", added);

        ObjectNode partAdded = mapper.createObjectNode();
        partAdded.put("type", "response.content_part.added");
        partAdded.put("item_id", itemId);
        partAdded.put("output_index", outputIndex);
        partAdded.put("content_index", 0);
        ObjectNode emptyPart = mapper.createObjectNode();
        emptyPart.put("type", "output_text");
        emptyPart.put("text", "");
        emptyPart.set("annotations", mapper.createArrayNode());
        partAdded.set("part", emptyPart);
        sendEvent(emitter, "response.content_part.added", partAdded);

        ObjectNode delta = mapper.createObjectNode();
        delta.put("type", "response.output_text.delta");
        delta.put("item_id", itemId);
        delta.put("output_index", outputIndex);
        delta.put("content_index", 0);
        delta.put("delta", text);
        sendEvent(emitter, "response.output_text.delta", delta);

        ObjectNode textDone = mapper.createObjectNode();
        textDone.put("type", "response.output_text.done");
        textDone.put("item_id", itemId);
        textDone.put("output_index", outputIndex);
        textDone.put("content_index", 0);
        textDone.put("text", text);
        sendEvent(emitter, "response.output_text.done", textDone);

        ObjectNode partDone = mapper.createObjectNode();
        partDone.put("type", "response.content_part.done");
        partDone.put("item_id", itemId);
        partDone.put("output_index", outputIndex);
        partDone.put("content_index", 0);
        ObjectNode fullPart = mapper.createObjectNode();
        fullPart.put("type", "output_text");
        fullPart.put("text", text);
        fullPart.set("annotations", mapper.createArrayNode());
        partDone.set("part", fullPart);
        sendEvent(emitter, "response.content_part.done", partDone);

        ObjectNode itemDone = mapper.createObjectNode();
        itemDone.put("type", "response.output_item.done");
        itemDone.put("output_index", outputIndex);
        itemDone.set("item", buildMessageItem(itemId, text, "completed"));
        sendEvent(emitter, "response.output_item.done", itemDone);
    }

    private void emitFunctionCallItem(SseEmitter emitter, String itemId, int outputIndex,
                                      String callId, String name, String args) throws IOException {
        ObjectNode added = mapper.createObjectNode();
        added.put("type", "response.output_item.added");
        added.put("output_index", outputIndex);
        added.set("item", buildFunctionCallItem(itemId, callId, name, "", "in_progress"));
        sendEvent(emitter, "response.output_item.added", added);

        if (args != null && !args.isEmpty()) {
            ObjectNode delta = mapper.createObjectNode();
            delta.put("type", "response.function_call_arguments.delta");
            delta.put("item_id", itemId);
            delta.put("output_index", outputIndex);
            delta.put("delta", args);
            sendEvent(emitter, "response.function_call_arguments.delta", delta);
        }

        ObjectNode argsDone = mapper.createObjectNode();
        argsDone.put("type", "response.function_call_arguments.done");
        argsDone.put("item_id", itemId);
        argsDone.put("output_index", outputIndex);
        argsDone.put("arguments", args == null ? "" : args);
        sendEvent(emitter, "response.function_call_arguments.done", argsDone);

        ObjectNode itemDone = mapper.createObjectNode();
        itemDone.put("type", "response.output_item.done");
        itemDone.put("output_index", outputIndex);
        itemDone.set("item", buildFunctionCallItem(itemId, callId, name, args, "completed"));
        sendEvent(emitter, "response.output_item.done", itemDone);
    }

    // ── item builders (used in both per-event items and the final completed.output[]) ──

    private ObjectNode buildReasoningItem(String itemId, String text, String status) {
        ObjectNode item = mapper.createObjectNode();
        item.put("id", itemId);
        item.put("type", "reasoning");
        item.put("status", status);
        ArrayNode summary = mapper.createArrayNode();
        if (text != null && !text.isEmpty()) {
            ObjectNode summaryItem = mapper.createObjectNode();
            summaryItem.put("type", "summary_text");
            summaryItem.put("text", text);
            summary.add(summaryItem);
        }
        item.set("summary", summary);
        return item;
    }

    private ObjectNode buildMessageItem(String itemId, String text, String status) {
        ObjectNode item = mapper.createObjectNode();
        item.put("id", itemId);
        item.put("type", "message");
        item.put("status", status);
        item.put("role", "assistant");
        ArrayNode content = mapper.createArrayNode();
        if (text != null && !text.isEmpty()) {
            ObjectNode part = mapper.createObjectNode();
            part.put("type", "output_text");
            part.put("text", text);
            part.set("annotations", mapper.createArrayNode());
            content.add(part);
        }
        item.set("content", content);
        return item;
    }

    private ObjectNode buildFunctionCallItem(String itemId, String callId, String name, String args, String status) {
        ObjectNode item = mapper.createObjectNode();
        item.put("id", itemId);
        item.put("type", "function_call");
        item.put("status", status);
        item.put("call_id", callId);
        item.put("name", name);
        item.put("arguments", args == null ? "" : args);
        return item;
    }

    private ObjectNode wrapResponse(String id, String status, String model, long createdAt,
                                    ArrayNode output, JsonNode usage) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("type", "response." + status);
        ObjectNode response = mapper.createObjectNode();
        response.put("id", id);
        response.put("object", "response");
        response.put("created_at", createdAt);
        response.put("status", status);
        response.put("model", model);
        response.set("output", output);
        response.set("usage", chatUsageToResponsesUsage(usage));
        payload.set("response", response);
        return payload;
    }

    private ObjectNode chatUsageToResponsesUsage(JsonNode chatUsage) {
        ObjectNode usage = mapper.createObjectNode();
        long input = chatUsage == null ? 0 : chatUsage.path("prompt_tokens").asLong(0);
        long output = chatUsage == null ? 0 : chatUsage.path("completion_tokens").asLong(0);
        long total = chatUsage == null ? input + output : chatUsage.path("total_tokens").asLong(input + output);
        usage.put("input_tokens", input);
        usage.put("output_tokens", output);
        usage.put("total_tokens", total);
        ObjectNode inputDetails = mapper.createObjectNode();
        inputDetails.put("cached_tokens", chatUsage == null ? 0
                : chatUsage.path("prompt_tokens_details").path("cached_tokens").asLong(0));
        usage.set("input_tokens_details", inputDetails);
        ObjectNode outputDetails = mapper.createObjectNode();
        outputDetails.put("reasoning_tokens", chatUsage == null ? 0
                : chatUsage.path("completion_tokens_details").path("reasoning_tokens").asLong(0));
        usage.set("output_tokens_details", outputDetails);
        return usage;
    }

    private String extractText(JsonNode message) {
        if (message == null || message.isNull()) return "";
        JsonNode content = message.get("content");
        if (content == null || content.isNull()) return "";
        if (content.isTextual()) {
            String text = content.asText();
            // Strip leading <think>...</think> if present (some providers inline it).
            return stripLeadingThink(text);
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.path("type").asText("").endsWith("text") && part.has("text")) {
                    sb.append(part.path("text").asText(""));
                }
            }
            return stripLeadingThink(sb.toString());
        }
        return "";
    }

    private String extractReasoning(JsonNode message) {
        if (message == null || message.isNull()) return null;
        // DeepSeek / Kimi / Zhipu put it under reasoning_content.
        if (message.has("reasoning_content") && message.get("reasoning_content").isTextual()) {
            String r = message.get("reasoning_content").asText().trim();
            if (!r.isEmpty()) return r;
        }
        // Some providers nest reasoning under message.reasoning.
        if (message.has("reasoning") && message.get("reasoning").isTextual()) {
            String r = message.get("reasoning").asText().trim();
            if (!r.isEmpty()) return r;
        }
        // Inline <think>...</think> block at the start of content.
        if (message.has("content") && message.get("content").isTextual()) {
            String content = message.get("content").asText();
            String think = extractLeadingThink(content);
            if (think != null && !think.isBlank()) return think;
        }
        return null;
    }

    private static String stripLeadingThink(String text) {
        if (text == null) return "";
        String trimmed = text.stripLeading();
        if (!trimmed.startsWith("<think>")) return text;
        int end = trimmed.indexOf("</think>");
        if (end < 0) return text;
        return trimmed.substring(end + "</think>".length()).stripLeading();
    }

    private static String extractLeadingThink(String text) {
        if (text == null) return null;
        String trimmed = text.stripLeading();
        if (!trimmed.startsWith("<think>")) return null;
        int end = trimmed.indexOf("</think>");
        if (end < 0) return null;
        return trimmed.substring("<think>".length(), end).trim();
    }

    private void sendEvent(SseEmitter emitter, String eventName, JsonNode data) throws IOException {
        emitter.send(SseEmitter.event()
                .name(eventName)
                .data(data, MediaType.APPLICATION_JSON));
    }
}
