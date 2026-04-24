package com.paicoding.paiswitch.service;

import com.paicoding.paiswitch.domain.dto.ProviderDto;
import com.paicoding.paiswitch.domain.entity.ModelProvider;
import com.paicoding.paiswitch.domain.entity.UserConfig;
import com.paicoding.paiswitch.repository.ApiKeyRepository;
import com.paicoding.paiswitch.repository.ModelProviderRepository;
import com.paicoding.paiswitch.repository.UserConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderServiceTest {

    @Test
    void shouldSyncSettingsWhenUpdatingCurrentProviderConfig() {
        ModelProviderRepository providerRepository = mock(ModelProviderRepository.class);
        ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
        UserConfigRepository configRepository = mock(UserConfigRepository.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        SettingsWriterService settingsWriterService = mock(SettingsWriterService.class);

        ModelProvider deepseek = ModelProvider.builder()
                .id(2L)
                .code("deepseek")
                .name("DeepSeek")
                .baseUrl("https://api.deepseek.com/anthropic")
                .modelName("deepseek-chat")
                .isBuiltin(true)
                .isActive(true)
                .build();
        UserConfig config = UserConfig.builder()
                .id(1L)
                .currentProvider(deepseek)
                .build();

        when(providerRepository.findByCode("deepseek")).thenReturn(Optional.of(deepseek));
        when(providerRepository.save(deepseek)).thenReturn(deepseek);
        when(configRepository.findByUserId(1L)).thenReturn(Optional.of(config));

        ProviderService service = new ProviderService(
                providerRepository,
                apiKeyRepository,
                configRepository,
                encryptionService,
                settingsWriterService
        );

        service.updateProviderConfig(1L, "deepseek", ProviderDto.ConfigUpdateRequest.builder()
                .modelName("deepseek-v4-pro")
                .build());

        verify(settingsWriterService).writeToSettings(1L, deepseek);
    }
}
