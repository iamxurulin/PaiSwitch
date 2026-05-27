package com.paicoding.paiswitch.service;

import com.paicoding.paiswitch.domain.dto.SwitchDto;
import com.paicoding.paiswitch.domain.entity.ModelProvider;
import com.paicoding.paiswitch.domain.entity.User;
import com.paicoding.paiswitch.domain.entity.UserConfig;
import com.paicoding.paiswitch.domain.enums.SwitchType;
import com.paicoding.paiswitch.domain.enums.TargetTool;
import com.paicoding.paiswitch.repository.ModelProviderRepository;
import com.paicoding.paiswitch.repository.SwitchHistoryRepository;
import com.paicoding.paiswitch.repository.UserConfigRepository;
import com.paicoding.paiswitch.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SwitchServiceTest {

    @Test
    void shouldRefreshSettingsWhenSwitchingToAlreadyCurrentClaudeProvider() {
        UserRepository userRepository = mock(UserRepository.class);
        UserConfigRepository configRepository = mock(UserConfigRepository.class);
        ModelProviderRepository providerRepository = mock(ModelProviderRepository.class);
        SwitchHistoryRepository switchHistoryRepository = mock(SwitchHistoryRepository.class);
        ConfigService configService = mock(ConfigService.class);
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        SettingsWriterService settingsWriterService = mock(SettingsWriterService.class);
        CodexSettingsWriterService codexSettingsWriterService = mock(CodexSettingsWriterService.class);

        User user = User.builder().id(1L).username("admin").build();
        ModelProvider deepseek = ModelProvider.builder()
                .id(2L)
                .code("deepseek")
                .name("DeepSeek")
                .baseUrl("https://api.deepseek.com/anthropic")
                .modelName("deepseek-v4-pro")
                .isActive(true)
                .targetTool(TargetTool.CLAUDE_CODE)
                .build();
        UserConfig config = UserConfig.builder()
                .id(1L)
                .user(user)
                .currentProvider(deepseek)
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(providerRepository.findByCodeAndTargetTool("deepseek", TargetTool.CLAUDE_CODE))
                .thenReturn(Optional.of(deepseek));
        when(configRepository.findByUserId(1L)).thenReturn(Optional.of(config));

        SwitchService service = new SwitchService(
                userRepository,
                configRepository,
                providerRepository,
                switchHistoryRepository,
                configService,
                apiKeyService,
                settingsWriterService,
                codexSettingsWriterService
        );

        SwitchDto.SwitchResult result = service.switchToProvider(
                1L,
                "deepseek",
                TargetTool.CLAUDE_CODE,
                SwitchType.MANUAL,
                null,
                null
        );

        assertTrue(result.getSuccess());
        verify(settingsWriterService).writeToSettings(1L, deepseek);
        verify(apiKeyService).updateLastUsedAt(1L, "deepseek", TargetTool.CLAUDE_CODE);
    }

    @Test
    void shouldWriteCodexSettingsWhenSwitchingCodexProvider() {
        UserRepository userRepository = mock(UserRepository.class);
        UserConfigRepository configRepository = mock(UserConfigRepository.class);
        ModelProviderRepository providerRepository = mock(ModelProviderRepository.class);
        SwitchHistoryRepository switchHistoryRepository = mock(SwitchHistoryRepository.class);
        ConfigService configService = mock(ConfigService.class);
        ApiKeyService apiKeyService = mock(ApiKeyService.class);
        SettingsWriterService settingsWriterService = mock(SettingsWriterService.class);
        CodexSettingsWriterService codexSettingsWriterService = mock(CodexSettingsWriterService.class);

        User user = User.builder().id(1L).username("admin").build();
        ModelProvider claudeOfficial = ModelProvider.builder()
                .id(1L)
                .code("claude")
                .name("Claude")
                .baseUrl("https://api.anthropic.com")
                .modelName("claude-sonnet-4")
                .isActive(true)
                .targetTool(TargetTool.CLAUDE_CODE)
                .build();
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
                .currentProvider(claudeOfficial)
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(providerRepository.findByCodeAndTargetTool("deepseek", TargetTool.CODEX))
                .thenReturn(Optional.of(codexDeepseek));
        when(configRepository.findByUserId(1L)).thenReturn(Optional.of(config));
        when(configRepository.save(config)).thenReturn(config);

        SwitchService service = new SwitchService(
                userRepository,
                configRepository,
                providerRepository,
                switchHistoryRepository,
                configService,
                apiKeyService,
                settingsWriterService,
                codexSettingsWriterService
        );

        SwitchDto.SwitchResult result = service.switchToProvider(
                1L,
                "deepseek",
                TargetTool.CODEX,
                SwitchType.MANUAL,
                null,
                null
        );

        assertTrue(result.getSuccess());
        verify(codexSettingsWriterService).writeToSettings(1L, codexDeepseek);
        verify(apiKeyService).updateLastUsedAt(1L, "deepseek", TargetTool.CODEX);
    }
}
