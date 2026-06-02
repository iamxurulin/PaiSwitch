package com.paicoding.paiswitch.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpstreamRequestConfigTest {

    @Test
    void shouldStripLoraIdQueryAndExposeHeader() {
        UpstreamRequestConfig config = UpstreamRequestConfig.fromBaseUrl(
                "https://maas-api.cn-huabei-1.xf-yun.com/v2?lora_id=resource-123");

        assertEquals("https://maas-api.cn-huabei-1.xf-yun.com/v2", config.baseUrl());
        assertEquals("resource-123", config.headers().get("lora_id"));
    }

    @Test
    void shouldIgnoreUnknownQueryParameters() {
        UpstreamRequestConfig config = UpstreamRequestConfig.fromBaseUrl(
                "https://maas-api.cn-huabei-1.xf-yun.com/v2?foo=bar");

        assertEquals("https://maas-api.cn-huabei-1.xf-yun.com/v2", config.baseUrl());
        assertTrue(config.headers().isEmpty());
    }
}
