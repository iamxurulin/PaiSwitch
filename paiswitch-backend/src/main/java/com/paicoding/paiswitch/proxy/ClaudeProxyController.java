package com.paicoding.paiswitch.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Local proxy that lets Claude Code CLI talk to providers that only expose an
 * OpenAI chat-completions API (e.g. apifree.ai SkyClaw).
 * <p>
 * Claude Code's {@code ANTHROPIC_BASE_URL} in {@code ~/.claude/settings.json} is
 * rewritten to {@code http://127.0.0.1:8086/claude-proxy/{providerCode}} so its
 * {@code POST /v1/messages} requests land here. The proxy:
 * <ol>
 *   <li>Receives an Anthropic Messages-API JSON body (+ {@code x-api-key} header)</li>
 *   <li>Translates it into an OpenAI chat-completions request</li>
 *   <li>Forwards to the real provider (non-streaming upstream)</li>
 *   <li>Synthesizes the Anthropic SSE event sequence Claude Code expects —
 *       including {@code usage.input_tokens} / {@code usage.output_tokens}
 *       (mapped from OpenAI's {@code prompt_tokens} / {@code completion_tokens})
 *       so Claude Code doesn't crash on {@code _.input_tokens}.</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/claude-proxy")
@RequiredArgsConstructor
public class ClaudeProxyController {

    private final ClaudeProxyService proxyService;

    @RequestMapping(value = "/{providerCode}", method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<Void> probe(@PathVariable String providerCode) {
        log.debug("Claude proxy probe: provider={}", providerCode);
        return ResponseEntity.ok().build();
    }

    @PostMapping(
            value = "/{providerCode}/v1/messages",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public SseEmitter createMessage(
            @PathVariable String providerCode,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey,
            @RequestHeader(value = "authorization", required = false) String authorization,
            @RequestHeader(value = "anthropic-version", required = false) String anthropicVersion,
            @RequestBody JsonNode requestBody
    ) {
        log.info("Claude proxy request: provider={}, hasKey={}, model={}, anthropic-version={}",
                providerCode,
                xApiKey != null || authorization != null,
                requestBody.path("model").asText("?"),
                anthropicVersion);
        String apiKey = resolveApiKey(xApiKey, authorization);
        return proxyService.handleMessagesRequest(providerCode, apiKey, requestBody);
    }

    private String resolveApiKey(String xApiKey, String authorization) {
        if (xApiKey != null && !xApiKey.isBlank()) {
            return xApiKey.trim();
        }
        if (authorization != null && !authorization.isBlank()) {
            String trimmed = authorization.trim();
            if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return trimmed.substring(7).trim();
            }
            return trimmed;
        }
        return null;
    }
}
