package com.paicoding.paiswitch.proxy;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Detects "HTTP 2xx + body-level error" responses from OpenAI-compatible
 * upstreams. Some providers (notably apifree.ai / SkyClaw) return HTTP 200
 * with an {@code error} object in the body and the real status in a top-level
 * {@code code} field — silently letting that through produces empty SSE
 * streams that Codex CLI / Claude Code render as "no response".
 */
public final class ChatResponseValidator {

    private ChatResponseValidator() {
    }

    /**
     * @return {@code null} if the body looks like a valid chat-completion
     *         response; otherwise a human-readable error message safe to
     *         surface to clients (Codex CLI / Claude Code / UI).
     */
    public static String describeBodyLevelError(JsonNode body) {
        if (body == null || body.isMissingNode() || body.isNull()) {
            return "Upstream returned empty body";
        }
        JsonNode error = body.get("error");
        if (error != null && error.isObject()) {
            return formatErrorObject(body, error);
        }
        int code = body.path("code").asInt(-1);
        if (code != -1 && (code < 200 || code >= 300)) {
            return "Upstream returned code=" + code + " in body";
        }
        JsonNode choices = body.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return "Upstream response missing 'choices' array (likely body-level error)";
        }
        return null;
    }

    private static String formatErrorObject(JsonNode body, JsonNode error) {
        String message = error.path("message").asText("");
        String type = error.path("type").asText("");
        String code = error.path("code").asText("");
        int httpInBody = body.path("code").asInt(0);

        StringBuilder sb = new StringBuilder("Upstream error");
        if (httpInBody > 0) {
            sb.append(" (HTTP-in-body ").append(httpInBody).append(")");
        }
        if (!type.isEmpty() || !code.isEmpty()) {
            sb.append(" [");
            sb.append(type.isEmpty() ? code : type);
            if (!type.isEmpty() && !code.isEmpty() && !type.equals(code)) {
                sb.append("/").append(code);
            }
            sb.append("]");
        }
        if (!message.isEmpty()) {
            sb.append(": ").append(message);
        }
        return sb.toString();
    }
}
