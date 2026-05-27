package com.paicoding.paiswitch.proxy.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Converts an OpenAI Responses API request (sent by Codex CLI) into an
 * OpenAI Chat Completions API request (understood by DeepSeek, Kimi, etc.).
 * <p>
 * Mirrors the subset of cc-switch's {@code responses_to_chat_completions} that
 * actually appears in Codex traffic. Aggressively skips features no current
 * Codex version emits (audio inputs, image_url generation, etc.).
 */
@Component
public class RequestTransformer {

    private final ObjectMapper mapper = new ObjectMapper();

    public JsonNode responsesToChat(JsonNode body) {
        ObjectNode out = mapper.createObjectNode();

        if (body.has("model")) {
            out.set("model", body.get("model"));
        }

        // Build messages: instructions -> system, input items -> user/assistant/tool messages.
        ArrayNode messages = mapper.createArrayNode();
        appendInstructionsAsSystem(body, messages);
        appendInputAsMessages(body, messages);
        out.set("messages", collapseLeadingSystemMessages(messages));

        // Token caps.
        if (body.has("max_output_tokens")) {
            out.set("max_tokens", body.get("max_output_tokens"));
        }
        if (body.has("max_tokens")) {
            out.set("max_tokens", body.get("max_tokens"));
        }

        // Simple passthrough fields.
        for (String key : new String[]{"temperature", "top_p", "stream", "user"}) {
            if (body.has(key)) {
                out.set(key, body.get(key));
            }
        }

        // Reasoning effort -> chat-completions reasoning_effort (best-effort).
        JsonNode reasoning = body.get("reasoning");
        if (reasoning != null && reasoning.has("effort")) {
            out.set("reasoning_effort", reasoning.get("effort"));
        }

        // Tools.
        if (body.has("tools") && body.get("tools").isArray()) {
            ArrayNode chatTools = mapper.createArrayNode();
            for (JsonNode tool : body.get("tools")) {
                JsonNode chatTool = responsesToolToChatTool(tool);
                if (chatTool != null) {
                    chatTools.add(chatTool);
                }
            }
            if (!chatTools.isEmpty()) {
                out.set("tools", chatTools);
            }
        }
        if (body.has("tool_choice")) {
            out.set("tool_choice", responsesToolChoiceToChat(body.get("tool_choice")));
        }

        // Streaming usage opt-in.
        if (out.path("stream").asBoolean(false)) {
            ObjectNode streamOpts = out.has("stream_options") && out.get("stream_options").isObject()
                    ? (ObjectNode) out.get("stream_options")
                    : mapper.createObjectNode();
            streamOpts.put("include_usage", true);
            out.set("stream_options", streamOpts);
        }

        return out;
    }

    private void appendInstructionsAsSystem(JsonNode body, ArrayNode messages) {
        JsonNode instructions = body.get("instructions");
        String text = instructionText(instructions);
        if (!text.isEmpty()) {
            ObjectNode sys = mapper.createObjectNode();
            sys.put("role", "system");
            sys.put("content", text);
            messages.add(sys);
        }
    }

    private String instructionText(JsonNode value) {
        if (value == null || value.isNull()) return "";
        if (value.isTextual()) return value.asText();
        if (value.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : value) {
                if (item.isTextual()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(item.asText());
                } else if (item.has("text")) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(item.get("text").asText(""));
                }
            }
            return sb.toString();
        }
        return "";
    }

    private void appendInputAsMessages(JsonNode body, ArrayNode messages) {
        JsonNode input = body.get("input");
        if (input == null || input.isNull()) return;

        if (input.isTextual()) {
            ObjectNode user = mapper.createObjectNode();
            user.put("role", "user");
            user.put("content", input.asText());
            messages.add(user);
            return;
        }
        if (!input.isArray()) return;

        for (JsonNode item : input) {
            appendInputItem(item, messages);
        }
    }

    private void appendInputItem(JsonNode item, ArrayNode messages) {
        if (item == null || !item.isObject()) return;

        String type = item.path("type").asText("");
        // Responses items observed from Codex: "message", "function_call", "function_call_output".
        if ("message".equals(type) || type.isEmpty()) {
            ObjectNode chatMsg = responsesMessageItemToChatMessage(item);
            if (chatMsg != null) {
                messages.add(chatMsg);
            }
        } else if ("function_call".equals(type)) {
            // Convert to assistant message with tool_calls array.
            String callId = item.path("call_id").asText(item.path("id").asText(""));
            String name = item.path("name").asText("");
            String args = item.path("arguments").asText("");
            ObjectNode assistant = mapper.createObjectNode();
            assistant.put("role", "assistant");
            assistant.putNull("content");
            ArrayNode toolCalls = mapper.createArrayNode();
            ObjectNode toolCall = mapper.createObjectNode();
            toolCall.put("id", callId);
            toolCall.put("type", "function");
            ObjectNode fn = mapper.createObjectNode();
            fn.put("name", name);
            fn.put("arguments", args);
            toolCall.set("function", fn);
            toolCalls.add(toolCall);
            assistant.set("tool_calls", toolCalls);
            messages.add(assistant);
        } else if ("function_call_output".equals(type)) {
            ObjectNode toolMsg = mapper.createObjectNode();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", item.path("call_id").asText(""));
            toolMsg.put("content", item.path("output").asText(""));
            messages.add(toolMsg);
        }
        // "reasoning" items are dropped — chat completions doesn't take them as input.
    }

    private ObjectNode responsesMessageItemToChatMessage(JsonNode item) {
        String role = responsesRoleToChatRole(item.path("role").asText("user"));
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", role);

        JsonNode content = item.get("content");
        if (content == null || content.isNull()) {
            msg.put("content", "");
            return msg;
        }
        if (content.isTextual()) {
            msg.put("content", content.asText());
            return msg;
        }
        if (content.isArray()) {
            // Collect all text-bearing parts into a single string; chat-completions
            // multimodal parts use a different shape that most non-OpenAI providers
            // don't implement, so we flatten to text for safety.
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                String partType = part.path("type").asText("");
                if (partType.endsWith("_text") || partType.equals("text")) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(part.path("text").asText(""));
                }
            }
            msg.put("content", sb.toString());
            return msg;
        }
        msg.put("content", content.toString());
        return msg;
    }

    private String responsesRoleToChatRole(String role) {
        return switch (role) {
            case "developer" -> "system";
            case "system", "user", "assistant", "tool" -> role;
            default -> "user";
        };
    }

    /**
     * Merge adjacent system messages at the head into one. Some providers
     * (DeepSeek, Kimi) reject multiple system messages.
     */
    private ArrayNode collapseLeadingSystemMessages(ArrayNode messages) {
        if (messages.size() < 2) return messages;
        List<String> leadingSystem = new ArrayList<>();
        int firstNonSystem = 0;
        for (JsonNode msg : messages) {
            if ("system".equals(msg.path("role").asText())) {
                leadingSystem.add(msg.path("content").asText(""));
                firstNonSystem++;
            } else {
                break;
            }
        }
        if (leadingSystem.size() < 2) return messages;

        ArrayNode out = mapper.createArrayNode();
        ObjectNode merged = mapper.createObjectNode();
        merged.put("role", "system");
        merged.put("content", String.join("\n\n", leadingSystem));
        out.add(merged);
        Iterator<JsonNode> it = messages.iterator();
        int skip = firstNonSystem;
        while (it.hasNext()) {
            JsonNode m = it.next();
            if (skip > 0) { skip--; continue; }
            out.add(m);
        }
        return out;
    }

    private JsonNode responsesToolToChatTool(JsonNode tool) {
        if (tool == null || !tool.isObject()) return null;
        // Responses tool: { type: "function", name, description, parameters, strict? }
        // Chat tool:      { type: "function", function: { name, description, parameters, strict? } }
        String type = tool.path("type").asText("function");
        if (!"function".equals(type)) return null;

        ObjectNode out = mapper.createObjectNode();
        out.put("type", "function");
        ObjectNode fn = mapper.createObjectNode();
        if (tool.has("name")) fn.set("name", tool.get("name"));
        if (tool.has("description")) fn.set("description", tool.get("description"));
        if (tool.has("parameters")) fn.set("parameters", tool.get("parameters"));
        if (tool.has("strict")) fn.set("strict", tool.get("strict"));
        out.set("function", fn);
        return out;
    }

    private JsonNode responsesToolChoiceToChat(JsonNode toolChoice) {
        if (toolChoice == null) return null;
        if (toolChoice.isTextual()) return toolChoice; // "auto" / "none" / "required"
        if (toolChoice.isObject() && "function".equals(toolChoice.path("type").asText(""))) {
            ObjectNode out = mapper.createObjectNode();
            out.put("type", "function");
            ObjectNode fn = mapper.createObjectNode();
            if (toolChoice.has("name")) fn.set("name", toolChoice.get("name"));
            out.set("function", fn);
            return out;
        }
        return toolChoice;
    }
}
