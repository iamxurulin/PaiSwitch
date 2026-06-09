package com.paicoding.paiswitch.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * XFYUN MaaS is OpenAI-compatible, but its function-calling surface is narrower
 * than Claude Code's Anthropic Messages tool protocol.
 */
final class XfyunRequestAdapter {

    private static final String PROVIDER_CODE = "xfyun-maas";
    private static final int MAX_FUNCTION_NAME_LENGTH = 32;

    private XfyunRequestAdapter() {
    }

    static AdaptedRequest adapt(String providerCode, ObjectNode request) {
        if (!PROVIDER_CODE.equalsIgnoreCase(providerCode)) {
            return new AdaptedRequest(request, Map.of(), false);
        }

        if (!supportsTools(request.path("model").asText(""))) {
            stripUnsupportedToolProtocol(request);
            return new AdaptedRequest(request, Map.of(), true);
        }

        Map<String, String> sanitizedToOriginal = sanitizeToolNames(request);
        normalizeToolChoice(request);
        return new AdaptedRequest(request, sanitizedToOriginal, false);
    }

    private static boolean supportsTools(String model) {
        String value = model == null ? "" : model.toLowerCase();
        return value.contains("deepseekv32")
                || value.contains("deepseek-v3.2")
                || value.contains("glm47")
                || value.contains("glm4.7")
                || value.contains("glm-4.7");
    }

    private static void stripUnsupportedToolProtocol(ObjectNode request) {
        request.remove("tools");
        request.remove("tool_choice");

        JsonNode messages = request.get("messages");
        if (messages == null || !messages.isArray()) {
            return;
        }
        for (JsonNode message : messages) {
            if (!message.isObject()) {
                continue;
            }
            ObjectNode msg = (ObjectNode) message;
            if ("tool".equals(msg.path("role").asText(""))) {
                String toolCallId = msg.path("tool_call_id").asText("");
                String content = msg.path("content").asText("");
                msg.put("role", "user");
                msg.put("content", "[tool_result " + toolCallId + "]\n" + content);
                msg.remove("tool_call_id");
                continue;
            }
            JsonNode toolCalls = msg.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
                String content = msg.path("content").asText("");
                msg.put("content", appendToolCallsAsText(content, toolCalls));
                msg.remove("tool_calls");
            }
        }
    }

    private static String appendToolCallsAsText(String content, JsonNode toolCalls) {
        StringBuilder sb = new StringBuilder(content == null ? "" : content);
        for (JsonNode call : toolCalls) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("[tool_call ")
                    .append(call.path("id").asText(""))
                    .append(" ")
                    .append(call.path("function").path("name").asText(""))
                    .append("]\n")
                    .append(call.path("function").path("arguments").asText(""));
        }
        return sb.toString();
    }

    private static Map<String, String> sanitizeToolNames(ObjectNode request) {
        Map<String, String> originalToSanitized = new HashMap<>();
        Map<String, String> sanitizedToOriginal = new HashMap<>();
        Set<String> used = new HashSet<>();

        JsonNode tools = request.get("tools");
        if (tools != null && tools.isArray()) {
            for (JsonNode tool : tools) {
                JsonNode fn = tool.path("function");
                if (!fn.isObject()) {
                    continue;
                }
                ObjectNode function = (ObjectNode) fn;
                String original = function.path("name").asText("");
                String sanitized = sanitizedName(original, used);
                function.put("name", sanitized);
                originalToSanitized.put(original, sanitized);
                sanitizedToOriginal.put(sanitized, original);
            }
        }

        JsonNode messages = request.get("messages");
        if (messages != null && messages.isArray()) {
            for (JsonNode message : messages) {
                sanitizeMessageToolNames(message, originalToSanitized, sanitizedToOriginal, used);
            }
        }
        sanitizeToolChoiceName(request.get("tool_choice"), originalToSanitized, sanitizedToOriginal, used);
        return sanitizedToOriginal;
    }

    private static void sanitizeMessageToolNames(JsonNode message,
                                                 Map<String, String> originalToSanitized,
                                                 Map<String, String> sanitizedToOriginal,
                                                 Set<String> used) {
        JsonNode toolCalls = message.path("tool_calls");
        if (!toolCalls.isArray()) {
            return;
        }
        for (JsonNode call : toolCalls) {
            JsonNode fn = call.path("function");
            if (!fn.isObject()) {
                continue;
            }
            ObjectNode function = (ObjectNode) fn;
            String original = function.path("name").asText("");
            String sanitized = originalToSanitized.computeIfAbsent(original, name -> sanitizedName(name, used));
            sanitizedToOriginal.putIfAbsent(sanitized, original);
            function.put("name", sanitized);
        }
    }

    private static void sanitizeToolChoiceName(JsonNode toolChoice,
                                               Map<String, String> originalToSanitized,
                                               Map<String, String> sanitizedToOriginal,
                                               Set<String> used) {
        if (toolChoice == null || !toolChoice.isObject()) {
            return;
        }
        JsonNode fn = toolChoice.path("function");
        if (!fn.isObject()) {
            return;
        }
        ObjectNode function = (ObjectNode) fn;
        String original = function.path("name").asText("");
        String sanitized = originalToSanitized.computeIfAbsent(original, name -> sanitizedName(name, used));
        sanitizedToOriginal.putIfAbsent(sanitized, original);
        function.put("name", sanitized);
    }

    private static void normalizeToolChoice(ObjectNode request) {
        JsonNode toolChoice = request.get("tool_choice");
        if (toolChoice == null || toolChoice.isTextual()) {
            return;
        }
        request.put("tool_choice", "auto");
    }

    private static String sanitizedName(String original, Set<String> used) {
        String base = original == null ? "" : original.replaceAll("[^A-Za-z0-9_]", "_");
        if (base.isBlank()) {
            base = "tool";
        }
        if (base.length() > MAX_FUNCTION_NAME_LENGTH) {
            base = base.substring(0, MAX_FUNCTION_NAME_LENGTH);
        }
        if (used.add(base)) {
            return base;
        }

        String suffix = "_" + hash8(original);
        int prefixLength = Math.max(1, MAX_FUNCTION_NAME_LENGTH - suffix.length());
        String candidate = base.substring(0, Math.min(base.length(), prefixLength)) + suffix;
        int counter = 2;
        while (!used.add(candidate)) {
            String numbered = "_" + counter++;
            int len = Math.max(1, MAX_FUNCTION_NAME_LENGTH - suffix.length() - numbered.length());
            candidate = base.substring(0, Math.min(base.length(), len)) + suffix + numbered;
        }
        return candidate;
    }

    private static String hash8(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "00000000";
        }
    }

    record AdaptedRequest(ObjectNode request, Map<String, String> sanitizedToOriginal, boolean toolsStripped) {
        JsonNode restoreResponseToolNames(JsonNode response) {
            if (sanitizedToOriginal.isEmpty() || response == null || response.isMissingNode() || response.isNull()) {
                return response;
            }
            JsonNode copy = response.deepCopy();
            JsonNode choices = copy.path("choices");
            if (!choices.isArray()) {
                return copy;
            }
            for (JsonNode choice : choices) {
                JsonNode toolCalls = choice.path("message").path("tool_calls");
                if (!toolCalls.isArray()) {
                    continue;
                }
                for (JsonNode call : toolCalls) {
                    JsonNode fn = call.path("function");
                    if (fn.isObject()) {
                        ObjectNode function = (ObjectNode) fn;
                        String sanitized = function.path("name").asText("");
                        String original = sanitizedToOriginal.get(sanitized);
                        if (original != null && !original.isBlank()) {
                            function.put("name", original);
                        }
                    }
                }
            }
            return copy;
        }
    }
}
