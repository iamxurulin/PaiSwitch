package com.paicoding.paiswitch.proxy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resolves the URL Codex CLI should call to reach PaiSwitch's local proxy.
 * <p>
 * PaiSwitch's {@code server.port} is {@code 0} (OS-assigned) so we can't hardcode
 * the URL. We capture the bound port from {@link WebServerInitializedEvent} and
 * combine it with an optional override {@code paiswitch.codex.proxy.base-url}.
 */
@Slf4j
@Component
public class ProxyEndpointResolver {

    private static final String DEFAULT_HOST = "http://127.0.0.1";

    private final AtomicInteger boundPort = new AtomicInteger(0);

    @Value("${paiswitch.codex.proxy.base-url:}")
    private String overrideBaseUrl;

    @EventListener
    public void onWebServerInit(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort();
        boundPort.set(port);
        log.info("Codex proxy endpoint bound at {}:{}", DEFAULT_HOST, port);
    }

    /**
     * @return base URL for the proxy, e.g. {@code http://127.0.0.1:54321}.
     *         Falls back to the override property when explicitly set.
     */
    public String getProxyBaseUrl() {
        if (overrideBaseUrl != null && !overrideBaseUrl.isBlank()) {
            return trimTrailingSlash(overrideBaseUrl);
        }
        int port = boundPort.get();
        if (port <= 0) {
            // Should only happen if writer is called before the web server boots.
            log.warn("Codex proxy port not yet known; defaulting to :8086");
            return DEFAULT_HOST + ":8086";
        }
        return DEFAULT_HOST + ":" + port;
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
