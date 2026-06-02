package com.paicoding.paiswitch.proxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicoding.paiswitch.domain.entity.ModelProvider;
import com.paicoding.paiswitch.domain.enums.TargetTool;
import com.paicoding.paiswitch.proxy.transform.RequestTransformer;
import com.paicoding.paiswitch.proxy.transform.ResponseEventEmitter;
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
public class CodexProxyService {

    private static final long EMITTER_TIMEOUT_MS = 5 * 60 * 1000L; // 5 min

    private final ModelProviderRepository providerRepository;
    private final RequestTransformer requestTransformer;
    private final ResponseEventEmitter responseEventEmitter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private final ExecutorService workerPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "codex-proxy-worker");
        t.setDaemon(true);
        return t;
    });

    public SseEmitter handleResponsesRequest(String providerCode, String authorization, JsonNode requestBody) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);

        workerPool.submit(() -> {
            try {
                Optional<ModelProvider> providerOpt = providerRepository.findByCodeAndTargetTool(providerCode, TargetTool.CODEX);
                if (providerOpt.isEmpty()) {
                    fail(emitter, "Unknown Codex provider: " + providerCode, "provider_not_found");
                    return;
                }
                ModelProvider provider = providerOpt.get();
                if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
                    fail(emitter, "Provider has no base_url: " + providerCode, "invalid_provider");
                    return;
                }

                JsonNode chatRequest = requestTransformer.responsesToChat(requestBody);
                // Always non-streaming upstream for P1 (synthetic SSE downstream).
                // Must also drop stream_options: DeepSeek 400's "stream_options should
                // be set along with stream = true".
                com.fasterxml.jackson.databind.node.ObjectNode chatObj =
                        (com.fasterxml.jackson.databind.node.ObjectNode) chatRequest;
                chatObj.put("stream", false);
                chatObj.remove("stream_options");
                TokenBudgetPolicy.applyProviderMinimums(providerCode, chatObj);

                UpstreamRequestConfig upstreamConfig = UpstreamRequestConfig.fromBaseUrl(provider.getBaseUrl());
                String upstreamUrl = trimTrailingSlash(upstreamConfig.baseUrl()) + "/chat/completions";
                String requestJson = objectMapper.writeValueAsString(chatRequest);
                log.info("Forwarding to {} (model={}, bytes={})", upstreamUrl,
                        chatRequest.path("model").asText("?"), requestJson.length());

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(upstreamUrl))
                        .timeout(Duration.ofMinutes(2))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson));
                upstreamConfig.headers().forEach(reqBuilder::header);
                if (authorization != null && !authorization.isBlank()) {
                    reqBuilder.header("Authorization", authorization);
                }

                HttpResponse<String> upstream = httpClient.send(
                        reqBuilder.build(),
                        HttpResponse.BodyHandlers.ofString());

                int bodyLen = upstream.body() == null ? 0 : upstream.body().length();
                log.info("Upstream response: status={}, bytes={}, snippet={}",
                        upstream.statusCode(), bodyLen, truncate(upstream.body(), 500));

                if (upstream.statusCode() / 100 != 2) {
                    fail(emitter,
                            "Upstream returned " + upstream.statusCode() + ": " + truncate(upstream.body(), 200),
                            "upstream_error");
                    return;
                }

                JsonNode chatResponse = objectMapper.readTree(upstream.body());
                String bodyError = ChatResponseValidator.describeBodyLevelError(chatResponse);
                if (bodyError != null) {
                    log.warn("Upstream HTTP 2xx but body-level error: {}", bodyError);
                    fail(emitter, bodyError, "upstream_error");
                    return;
                }

                responseEventEmitter.emitFromChatCompletion(emitter, chatResponse, chatRequest.path("model").asText(""));
                emitter.complete();
            } catch (Throwable t) {
                log.error("Codex proxy failure for {}: {}", providerCode, t.toString(), t);
                fail(emitter, t.getMessage() == null ? t.toString() : t.getMessage(), "proxy_error");
            }
        });

        return emitter;
    }

    private void fail(SseEmitter emitter, String message, String errorType) {
        try {
            String payload = objectMapper.writeValueAsString(
                    objectMapper.createObjectNode()
                            .put("type", "response.failed")
                            .set("response", objectMapper.createObjectNode()
                                    .put("status", "failed")
                                    .set("error", objectMapper.createObjectNode()
                                            .put("type", errorType)
                                            .put("message", message))));
            emitter.send(SseEmitter.event().name("response.failed").data(payload, org.springframework.http.MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
            // best effort
        } finally {
            emitter.complete();
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
