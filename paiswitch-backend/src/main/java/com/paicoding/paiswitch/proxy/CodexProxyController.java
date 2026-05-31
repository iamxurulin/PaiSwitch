package com.paicoding.paiswitch.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Local proxy that translates between Codex CLI's Responses API and provider-native
 * chat-completions APIs (DeepSeek / Kimi / Zhipu / Qwen-Bailian).
 * <p>
 * Codex CLI's {@code base_url} in {@code ~/.codex/config.toml} is rewritten to
 * {@code http://127.0.0.1:8086/codex-proxy/{providerCode}/v1} so its requests
 * land here. The proxy:
 * <ol>
 *   <li>Receives {@code POST .../v1/responses} with a Responses API JSON body</li>
 *   <li>Transforms it into a chat-completions request</li>
 *   <li>Forwards to the real provider, reusing the {@code Authorization} header</li>
 *   <li>Translates the chat response back into Responses API SSE events</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/codex-proxy")
@RequiredArgsConstructor
public class CodexProxyController {

    private final CodexProxyService proxyService;

    @RequestMapping(value = "/{providerCode}/v1", method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<Void> probe(@PathVariable String providerCode) {
        log.debug("Codex proxy probe: provider={}", providerCode);
        return ResponseEntity.ok().build();
    }

    @PostMapping(
            value = "/{providerCode}/v1/responses",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public SseEmitter createResponse(
            @PathVariable String providerCode,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody JsonNode requestBody
    ) {
        log.info("Codex proxy request: provider={}, hasAuth={}, model={}",
                providerCode,
                authorization != null,
                requestBody.path("model").asText("?"));
        return proxyService.handleResponsesRequest(providerCode, authorization, requestBody);
    }
}
