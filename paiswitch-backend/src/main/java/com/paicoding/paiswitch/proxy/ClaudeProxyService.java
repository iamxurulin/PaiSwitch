package com.paicoding.paiswitch.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicoding.paiswitch.domain.entity.ModelProvider;
import com.paicoding.paiswitch.domain.enums.TargetTool;
import com.paicoding.paiswitch.proxy.transform.AnthropicEventEmitter;
import com.paicoding.paiswitch.proxy.transform.AnthropicToChatTransformer;
import com.paicoding.paiswitch.repository.ModelProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeProxyService {

    private static final long EMITTER_TIMEOUT_MS = 5 * 60 * 1000L; // 5 min

    private final ModelProviderRepository providerRepository;
    private final AnthropicToChatTransformer requestTransformer;
    private final AnthropicEventEmitter eventEmitter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private final ExecutorService workerPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "claude-proxy-worker");
        t.setDaemon(true);
        return t;
    });

    public SseEmitter handleMessagesRequest(String providerCode, String apiKey, JsonNode requestBody) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);

        workerPool.submit(() -> {
            try {
                Optional<ModelProvider> providerOpt =
                        providerRepository.findByCodeAndTargetTool(providerCode, TargetTool.CLAUDE_CODE);
                if (providerOpt.isEmpty()) {
                    eventEmitter.emitError(emitter, "Unknown Claude provider: " + providerCode, "provider_not_found");
                    return;
                }
                ModelProvider provider = providerOpt.get();
                if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
                    eventEmitter.emitError(emitter, "Provider has no base_url: " + providerCode, "invalid_provider");
                    return;
                }

                JsonNode chatRequest = requestTransformer.anthropicToChat(requestBody);
                ObjectNode chatObj = (ObjectNode) chatRequest;
                // Always non-streaming upstream (synthesize SSE downstream).
                // stream_options removed for the same reason as the Codex proxy: some
                // upstreams (e.g. DeepSeek) 400 if stream_options is set without stream=true.
                chatObj.put("stream", false);
                chatObj.remove("stream_options");
                TokenBudgetPolicy.applyProviderMinimums(providerCode, chatObj);

                // Capture the model the client asked for — used when emitting message_start
                // so Claude Code's UI shows e.g. "skywork-ai/skyclaw-v1" rather than upstream
                // echoing back something different.
                String requestedModel = chatRequest.path("model").asText(requestBody.path("model").asText(""));

                String upstreamUrl = trimTrailingSlash(provider.getBaseUrl()) + "/chat/completions";
                String requestJson = objectMapper.writeValueAsString(chatRequest);
                log.info("Forwarding to {} (model={}, bytes={})", upstreamUrl, requestedModel, requestJson.length());

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(upstreamUrl))
                        .timeout(Duration.ofMinutes(2))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson));
                if (apiKey != null && !apiKey.isBlank()) {
                    reqBuilder.header("Authorization", "Bearer " + apiKey);
                }

                HttpResponse<String> upstream = httpClient.send(
                        reqBuilder.build(),
                        HttpResponse.BodyHandlers.ofString());

                int bodyLen = upstream.body() == null ? 0 : upstream.body().length();
                log.info("Upstream response: status={}, bytes={}, snippet={}",
                        upstream.statusCode(), bodyLen, truncate(upstream.body(), 500));

                if (upstream.statusCode() / 100 != 2) {
                    eventEmitter.emitError(emitter,
                            "Upstream returned " + upstream.statusCode() + ": "
                                    + truncate(upstream.body(), 300),
                            "upstream_error");
                    return;
                }

                JsonNode chatResponse = objectMapper.readTree(upstream.body());
                String bodyError = ChatResponseValidator.describeBodyLevelError(chatResponse);
                if (bodyError != null) {
                    log.warn("Upstream HTTP 2xx but body-level error: {}", bodyError);
                    eventEmitter.emitError(emitter, bodyError, "upstream_error");
                    return;
                }

                eventEmitter.emitFromChatCompletion(emitter, chatResponse, requestedModel);
                emitter.complete();
            } catch (Throwable t) {
                log.error("Claude proxy failure for {}: {}", providerCode, t.toString(), t);
                eventEmitter.emitError(emitter,
                        t.getMessage() == null ? t.toString() : t.getMessage(),
                        "proxy_error");
            }
        });

        return emitter;
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
