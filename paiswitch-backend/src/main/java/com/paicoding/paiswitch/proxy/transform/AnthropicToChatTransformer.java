package com.paicoding.paiswitch.proxy.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Converts an Anthropic Messages API request (sent by Claude Code) into an
 * OpenAI Chat Completions API request (understood by apifree.ai, DeepSeek,
 * etc.).
 * <p>
 * Mirrors the Anthropic features Claude Code actually emits in practice:
 * <ul>
 *   <li>{@code system} (string or content-block array) → leading
 *       {@code role:"system"} message</li>
 *   <li>{@code messages[].content} either a plain string or an array of
 *       {@code text} / {@code tool_use} / {@code tool_result} blocks</li>
 *   <li>{@code tools} (Anthropic {@code {name, description, input_schema}}) →
 *       OpenAI {@code {type:"function", function:{name, description, parameters}}}</li>
 *   <li>{@code tool_choice} variants: auto / any / none / specific tool</li>
 * </ul>
 * Image blocks are dropped (apifree.ai/agent and similar OpenAI-compatible
 * providers don't accept Anthropic image shapes).
 */
@Component
public class AnthropicToChatTransformer {

    private final ObjectMapper mapper = new ObjectMapper();

    public JsonNode anthropicToChat(JsonNode body) {
        ObjectNode out = mapper.createObjectNode();

        if (body.has("model")) {
            out.set("model", body.get("model"));
        }

        ArrayNode messages = mapper.createArrayNode();
        appendSystemAsMessage(body.get("system"), messages);
        appendAnthropicMessages(body.get("messages"), messages);
        out.set("messages", messages);

        if (body.has("max_tokens")) {
            out.set("max_tokens", body.get("max_tokens"));
        }

        // `top_k` is an Anthropic-only sampling parameter. Several
        // OpenAI-compatible providers, including XFYUN MaaS, reject it as an
        // invalid request parameter.
        for (String key : new String[]{"temperature", "top_p", "stream", "user"}) {
            if (body.has(key)) {
                out.set(key, body.get(key));
            }
        }

        // Anthropic uses "stop_sequences"; OpenAI uses "stop".
        if (body.has("stop_sequences")) {
            out.set("stop", body.get("stop_sequences"));
        }

        // Tools.
        if (body.has("tools") && body.get("tools").isArray()) {
            ArrayNode chatTools = mapper.createArrayNode();
            for (JsonNode tool : body.get("tools")) {
                JsonNode chatTool = anthropicToolToChatTool(tool);
                if (chatTool != null) {
                    chatTools.add(chatTool);
                }
            }
            if (!chatTools.isEmpty()) {
                out.set("tools", chatTools);
            }
        }
        if (body.has("tool_choice")) {
            JsonNode mapped = anthropicToolChoiceToChat(body.get("tool_choice"));
            if (mapped != null) {
                out.set("tool_choice", mapped);
            }
        }

        // Streaming usage opt-in (Claude proxy still forces stream=false upstream,
        // but if a future caller leaves stream=true we still want usage included).
        if (out.path("stream").asBoolean(false)) {
            ObjectNode streamOpts = out.has("stream_options") && out.get("stream_options").isObject()
                    ? (ObjectNode) out.get("stream_options")
                    : mapper.createObjectNode();
            streamOpts.put("include_usage", true);
            out.set("stream_options", streamOpts);
        }

        return out;
    }

    // ── system ───────────────────────────────────────────────────────────────

    private void appendSystemAsMessage(JsonNode system, ArrayNode messages) {
        String text = systemToText(system);
        if (text.isEmpty()) {
            return;
        }
        ObjectNode sys = mapper.createObjectNode();
        sys.put("role", "system");
        sys.put("content", text);
        messages.add(sys);
    }

    private String systemToText(JsonNode system) {
        if (system == null || system.isNull()) return "";
        if (system.isTextual()) return system.asText();
        if (system.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : system) {
                if (block.isTextual()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(block.asText());
                } else if (block.isObject() && block.has("text")) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(block.get("text").asText(""));
                }
            }
            return sb.toString();
        }
        return "";
    }

    // ── messages ─────────────────────────────────────────────────────────────

    private void appendAnthropicMessages(JsonNode anthMessages, ArrayNode out) {
        if (anthMessages == null || !anthMessages.isArray()) return;

        for (JsonNode msg : anthMessages) {
            if (!msg.isObject()) continue;
            String role = msg.path("role").asText("user");
            JsonNode content = msg.get("content");

            // Plain string content: trivial pass-through.
            if (content == null || content.isNull()) {
                addSimpleMessage(out, role, "");
                continue;
            }
            if (content.isTextual()) {
                addSimpleMessage(out, role, content.asText());
                continue;
            }
            if (!content.isArray()) {
                addSimpleMessage(out, role, content.toString());
                continue;
            }

            // Content-block array. Anthropic allows interleaving text + tool_use
            // (assistant) or text + tool_result (user). OpenAI requires a different
            // shape for tool_calls and tool_result, so we have to split.
            if ("assistant".equals(role)) {
                explodeAssistantBlocks(content, out);
            } else if ("user".equals(role)) {
                explodeUserBlocks(content, out);
            } else {
                // Claude Code may inject system reminders inside messages after
                // user turns. Several OpenAI-compatible upstreams reject system
                // messages outside the leading top-level system slot, so keep
                // the content but carry it as a user message.
                addSimpleMessage(out, "user", joinTextBlocks(content));
            }
        }
    }

    private void addSimpleMessage(ArrayNode out, String role, String text) {
        ObjectNode m = mapper.createObjectNode();
        m.put("role", chatRoleOf(role));
        m.put("content", text);
        out.add(m);
    }

    /**
     * Assistant message: collect all text into the single {@code content} field,
     * collect every {@code tool_use} block into a single {@code tool_calls}
     * array. OpenAI requires assistant messages with tool_calls to keep their
     * text content non-null (empty string ok) and place tool_calls alongside.
     */
    private void explodeAssistantBlocks(JsonNode content, ArrayNode out) {
        StringBuilder textBuf = new StringBuilder();
        ArrayNode toolCalls = mapper.createArrayNode();

        for (JsonNode block : content) {
            if (!block.isObject()) continue;
            String type = block.path("type").asText("");
            switch (type) {
                case "text" -> {
                    if (textBuf.length() > 0) textBuf.append('\n');
                    textBuf.append(block.path("text").asText(""));
                }
                case "tool_use" -> {
                    ObjectNode call = mapper.createObjectNode();
                    call.put("id", block.path("id").asText(""));
                    call.put("type", "function");
                    ObjectNode fn = mapper.createObjectNode();
                    fn.put("name", block.path("name").asText(""));
                    JsonNode input = block.get("input");
                    String args;
                    try {
                        args = input == null || input.isNull() ? "{}" : mapper.writeValueAsString(input);
                    } catch (Exception e) {
                        args = "{}";
                    }
                    fn.put("arguments", args);
                    call.set("function", fn);
                    toolCalls.add(call);
                }
                // thinking / redacted_thinking blocks: drop (not useful upstream).
                default -> { }
            }
        }

        ObjectNode assistant = mapper.createObjectNode();
        assistant.put("role", "assistant");
        // If there are tool calls, keep content as a string (empty allowed); some
        // OpenAI-compatible servers reject `null` content alongside tool_calls.
        assistant.put("content", textBuf.toString());
        if (!toolCalls.isEmpty()) {
            assistant.set("tool_calls", toolCalls);
        }
        out.add(assistant);
    }

    /**
     * User message: tool_result blocks must each become a separate
     * {@code role:"tool"} message in OpenAI. Plain text blocks collapse into
     * a single {@code role:"user"} message (placed AFTER any tool messages so
     * the assistant has the tool results before the new user prompt — but in
     * practice Claude Code rarely sends both kinds in one user turn).
     */
    private void explodeUserBlocks(JsonNode content, ArrayNode out) {
        StringBuilder textBuf = new StringBuilder();
        boolean hadToolResult = false;

        for (JsonNode block : content) {
            if (!block.isObject()) continue;
            String type = block.path("type").asText("");
            switch (type) {
                case "text" -> {
                    if (textBuf.length() > 0) textBuf.append('\n');
                    textBuf.append(block.path("text").asText(""));
                }
                case "tool_result" -> {
                    hadToolResult = true;
                    ObjectNode tool = mapper.createObjectNode();
                    tool.put("role", "tool");
                    tool.put("tool_call_id", block.path("tool_use_id").asText(""));
                    tool.put("content", toolResultContentToText(block.get("content")));
                    out.add(tool);
                }
                case "image" -> {
                    // Anthropic image blocks: drop, surface as a placeholder so the
                    // model at least knows something was here.
                    if (textBuf.length() > 0) textBuf.append('\n');
                    textBuf.append("[image omitted by proxy]");
                }
                default -> { }
            }
        }

        if (textBuf.length() > 0 || !hadToolResult) {
            // Emit a user text message either when there is text, or as a
            // fallback for an empty content array (preserve message slot).
            ObjectNode user = mapper.createObjectNode();
            user.put("role", "user");
            user.put("content", textBuf.toString());
            out.add(user);
        }
    }

    private String toolResultContentToText(JsonNode content) {
        if (content == null || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if (block.isTextual()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(block.asText());
                } else if (block.isObject() && "text".equals(block.path("type").asText(""))) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(block.path("text").asText(""));
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private String joinTextBlocks(JsonNode content) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if (block.isTextual()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(block.asText());
            } else if (block.isObject() && "text".equals(block.path("type").asText(""))) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(block.path("text").asText(""));
            }
        }
        return sb.toString();
    }

    private String chatRoleOf(String anthropicRole) {
        return switch (anthropicRole) {
            case "user", "assistant", "tool" -> anthropicRole;
            case "system" -> "user";
            default -> "user";
        };
    }

    // ── tools ────────────────────────────────────────────────────────────────

    private JsonNode anthropicToolToChatTool(JsonNode tool) {
        if (tool == null || !tool.isObject()) return null;
        ObjectNode out = mapper.createObjectNode();
        out.put("type", "function");
        ObjectNode fn = mapper.createObjectNode();
        if (tool.has("name")) fn.set("name", tool.get("name"));
        if (tool.has("description")) fn.set("description", tool.get("description"));
        // Anthropic calls it `input_schema`; OpenAI calls it `parameters`.
        if (tool.has("input_schema")) {
            fn.set("parameters", tool.get("input_schema"));
        } else if (tool.has("parameters")) {
            fn.set("parameters", tool.get("parameters"));
        }
        out.set("function", fn);
        return out;
    }

    private JsonNode anthropicToolChoiceToChat(JsonNode toolChoice) {
        if (toolChoice == null || toolChoice.isNull()) return null;
        if (toolChoice.isTextual()) {
            return toolChoice; // pass through "auto" / "none" / "required"
        }
        if (!toolChoice.isObject()) return null;
        String type = toolChoice.path("type").asText("");
        return switch (type) {
            case "auto" -> mapper.getNodeFactory().textNode("auto");
            case "any" -> mapper.getNodeFactory().textNode("required");
            case "none" -> mapper.getNodeFactory().textNode("none");
            case "tool" -> {
                ObjectNode out = mapper.createObjectNode();
                out.put("type", "function");
                ObjectNode fn = mapper.createObjectNode();
                if (toolChoice.has("name")) fn.set("name", toolChoice.get("name"));
                out.set("function", fn);
                yield out;
            }
            default -> null;
        };
    }
}
