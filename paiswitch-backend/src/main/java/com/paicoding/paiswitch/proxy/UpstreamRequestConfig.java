package com.paicoding.paiswitch.proxy;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses provider base URLs that carry lightweight upstream request options.
 * Example: https://maas-api.cn-huabei-1.xf-yun.com/v2?lora_id=xxx
 */
public record UpstreamRequestConfig(String baseUrl, Map<String, String> headers) {

    private static final String LORA_ID = "lora_id";

    public static UpstreamRequestConfig fromBaseUrl(String rawBaseUrl) {
        String normalized = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        if (normalized.isEmpty() || !normalized.contains("?")) {
            return new UpstreamRequestConfig(normalized, Map.of());
        }

        URI uri = URI.create(normalized);
        Map<String, String> headers = parseHeaders(uri.getRawQuery());
        String cleanBaseUrl = stripQuery(uri);
        return new UpstreamRequestConfig(cleanBaseUrl, headers);
    }

    private static Map<String, String> parseHeaders(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }

        Map<String, String> headers = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            int equalsIndex = pair.indexOf('=');
            String rawName = equalsIndex >= 0 ? pair.substring(0, equalsIndex) : pair;
            String rawValue = equalsIndex >= 0 ? pair.substring(equalsIndex + 1) : "";
            String name = decode(rawName);
            String value = decode(rawValue);
            if (LORA_ID.equals(name) && !value.isBlank()) {
                headers.put(LORA_ID, value.trim());
            }
        }
        return Map.copyOf(headers);
    }

    private static String stripQuery(URI uri) {
        try {
            return new URI(uri.getScheme(), uri.getRawAuthority(), uri.getRawPath(), null, null).toString();
        } catch (Exception e) {
            String value = uri.toString();
            int queryIndex = value.indexOf('?');
            return queryIndex >= 0 ? value.substring(0, queryIndex) : value;
        }
    }

    private static String decode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
