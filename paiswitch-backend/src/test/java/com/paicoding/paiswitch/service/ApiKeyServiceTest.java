package com.paicoding.paiswitch.service;

import com.paicoding.paiswitch.domain.dto.ApiKeyDto;
import com.paicoding.paiswitch.domain.entity.ApiKey;
import com.paicoding.paiswitch.domain.entity.ModelProvider;
import com.paicoding.paiswitch.domain.entity.User;
import com.paicoding.paiswitch.domain.entity.UserConfig;
import com.paicoding.paiswitch.domain.enums.TargetTool;
import com.paicoding.paiswitch.repository.ApiKeyRepository;
import com.paicoding.paiswitch.repository.ModelProviderRepository;
import com.paicoding.paiswitch.repository.UserConfigRepository;
import com.paicoding.paiswitch.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyServiceTest {

    @Test
    void shouldSyncSettingsWhenSettingApiKeyForCurrentClaudeProvider() {
        ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        ModelProviderRepository providerRepository = mock(ModelProviderRepository.class);
        UserConfigRepository configRepository = mock(UserConfigRepository.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        SettingsWriterService settingsWriterService = mock(SettingsWriterService.class);
        CodexSettingsWriterService codexSettingsWriterService = mock(CodexSettingsWriterService.class);

        User user = User.builder()
                .id(1L)
                .username("admin")
                .build();
        ModelProvider deepseek = deepseekProvider();
        UserConfig config = UserConfig.builder()
                .id(1L)
                .user(user)
                .currentProvider(deepseek)
                .build();
        ApiKey savedApiKey = ApiKey.builder()
                .id(1L)
                .user(user)
                .provider(deepseek)
                .encryptedKey("encrypted")
                .keyHint("sk-***")
                .isValid(true)
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(providerRepository.findByCodeAndTargetTool("deepseek", TargetTool.CLAUDE_CODE))
                .thenReturn(Optional.of(deepseek));
        when(apiKeyRepository.findByUserIdAndProviderId(1L, 2L)).thenReturn(Optional.empty());
        when(encryptionService.encrypt("sk-new")).thenReturn("encrypted");
        when(encryptionService.getKeyHint("sk-new")).thenReturn("sk-***");
        when(apiKeyRepository.save(any(ApiKey.class))).thenReturn(savedApiKey);
        when(configRepository.findByUserId(1L)).thenReturn(Optional.of(config));

        ApiKeyService service = new ApiKeyService(
                apiKeyRepository,
                userRepository,
                providerRepository,
                configRepository,
                encryptionService,
                settingsWriterService,
                codexSettingsWriterService
        );

        service.setApiKey(1L,
                ApiKeyDto.SetKeyRequest.builder().providerCode("deepseek").apiKey("sk-new").build(),
                TargetTool.CLAUDE_CODE);

        verify(settingsWriterService).writeToSettings(1L, deepseek);
    }

    @Test
    void shouldSyncSettingsWhenDeletingApiKeyForCurrentClaudeProvider() {
        ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        ModelProviderRepository providerRepository = mock(ModelProviderRepository.class);
        UserConfigRepository configRepository = mock(UserConfigRepository.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        SettingsWriterService settingsWriterService = mock(SettingsWriterService.class);
        CodexSettingsWriterService codexSettingsWriterService = mock(CodexSettingsWriterService.class);

        ModelProvider deepseek = deepseekProvider();
        UserConfig config = UserConfig.builder()
                .id(1L)
                .currentProvider(deepseek)
                .build();

        when(providerRepository.findByCodeAndTargetTool("deepseek", TargetTool.CLAUDE_CODE))
                .thenReturn(Optional.of(deepseek));
        when(configRepository.findByUserId(1L)).thenReturn(Optional.of(config));

        ApiKeyService service = new ApiKeyService(
                apiKeyRepository,
                userRepository,
                providerRepository,
                configRepository,
                encryptionService,
                settingsWriterService,
                codexSettingsWriterService
        );

        service.deleteApiKey(1L, "deepseek", TargetTool.CLAUDE_CODE);

        verify(apiKeyRepository).deleteByUserIdAndProviderId(1L, 2L);
        verify(settingsWriterService).writeToSettings(1L, deepseek);
    }

    @Test
    void shouldSyncCodexSettingsWhenSettingApiKeyForCurrentCodexProvider() {
        ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        ModelProviderRepository providerRepository = mock(ModelProviderRepository.class);
        UserConfigRepository configRepository = mock(UserConfigRepository.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        SettingsWriterService settingsWriterService = mock(SettingsWriterService.class);
        CodexSettingsWriterService codexSettingsWriterService = mock(CodexSettingsWriterService.class);

        User user = User.builder().id(1L).username("admin").build();
        ModelProvider codexDeepseek = ModelProvider.builder()
                .id(20L)
                .code("deepseek")
                .name("DeepSeek")
                .baseUrl("https://api.deepseek.com")
                .modelName("deepseek-chat")
                .isActive(true)
                .targetTool(TargetTool.CODEX)
                .wireApi("chat")
                .providerKey("deepseek")
                .build();
        UserConfig config = UserConfig.builder()
                .id(1L)
                .user(user)
                .currentCodexProvider(codexDeepseek)
                .build();
        ApiKey saved = ApiKey.builder()
                .id(2L).user(user).provider(codexDeepseek)
                .encryptedKey("enc").keyHint("sk-***").isValid(true).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(providerRepository.findByCodeAndTargetTool("deepseek", TargetTool.CODEX))
                .thenReturn(Optional.of(codexDeepseek));
        when(apiKeyRepository.findByUserIdAndProviderId(1L, 20L)).thenReturn(Optional.empty());
        when(encryptionService.encrypt("sk-new")).thenReturn("enc");
        when(encryptionService.getKeyHint("sk-new")).thenReturn("sk-***");
        when(apiKeyRepository.save(any(ApiKey.class))).thenReturn(saved);
        when(configRepository.findByUserId(1L)).thenReturn(Optional.of(config));

        ApiKeyService service = new ApiKeyService(
                apiKeyRepository, userRepository, providerRepository, configRepository,
                encryptionService, settingsWriterService, codexSettingsWriterService);

        service.setApiKey(1L,
                ApiKeyDto.SetKeyRequest.builder().providerCode("deepseek").apiKey("sk-new").build(),
                TargetTool.CODEX);

        verify(codexSettingsWriterService).writeToSettings(1L, codexDeepseek);
    }

    private ModelProvider deepseekProvider() {
        return ModelProvider.builder()
                .id(2L)
                .code("deepseek")
                .name("DeepSeek")
                .baseUrl("https://api.deepseek.com/anthropic")
                .modelName("deepseek-v4-pro")
                .isActive(true)
                .targetTool(TargetTool.CLAUDE_CODE)
                .build();
    }
}
