package com.paicoding.paiswitch.service;

import com.paicoding.paiswitch.common.exception.BusinessException;
import com.paicoding.paiswitch.common.response.ResponseCode;
import com.paicoding.paiswitch.domain.dto.ProviderDto;
import com.paicoding.paiswitch.domain.dto.SwitchDto;
import com.paicoding.paiswitch.domain.entity.ModelProvider;
import com.paicoding.paiswitch.domain.entity.SwitchHistory;
import com.paicoding.paiswitch.domain.entity.User;
import com.paicoding.paiswitch.domain.entity.UserConfig;
import com.paicoding.paiswitch.domain.enums.BackupType;
import com.paicoding.paiswitch.domain.enums.SwitchType;
import com.paicoding.paiswitch.domain.enums.TargetTool;
import com.paicoding.paiswitch.repository.ModelProviderRepository;
import com.paicoding.paiswitch.repository.SwitchHistoryRepository;
import com.paicoding.paiswitch.repository.UserConfigRepository;
import com.paicoding.paiswitch.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class SwitchService {

    private final UserRepository userRepository;
    private final UserConfigRepository configRepository;
    private final ModelProviderRepository providerRepository;
    private final SwitchHistoryRepository switchHistoryRepository;
    private final ConfigService configService;
    private final ApiKeyService apiKeyService;
    private final SettingsWriterService settingsWriterService;
    private final CodexSettingsWriterService codexSettingsWriterService;

    @Transactional
    public SwitchDto.SwitchResult switchToProvider(Long userId, String providerCode, TargetTool tool,
                                                   SwitchType switchType, String aiPrompt, String clientInfo) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ResponseCode.USER_NOT_FOUND));

        ModelProvider targetProvider = providerRepository.findByCodeAndTargetTool(providerCode, tool)
                .orElseThrow(() -> new BusinessException(ResponseCode.PROVIDER_NOT_FOUND));

        if (!targetProvider.getIsActive()) {
            throw new BusinessException(ResponseCode.PROVIDER_INACTIVE);
        }

        UserConfig config = configRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ResponseCode.CONFIG_NOT_FOUND));

        ModelProvider fromProvider = currentProviderForTool(config, tool);

        boolean alreadyCurrent = fromProvider != null && fromProvider.getId().equals(targetProvider.getId());
        if (alreadyCurrent) {
            try {
                writeSettingsForTool(userId, targetProvider, tool);
                apiKeyService.updateLastUsedAt(userId, providerCode, tool);
            } catch (Exception e) {
                log.error("Failed to refresh settings for user {} tool {} provider {}: {}", userId, tool, providerCode, e.getMessage());
                return SwitchDto.SwitchResult.builder()
                        .success(false)
                        .message("Failed to refresh settings: " + e.getMessage())
                        .currentProvider(mapToProviderInfo(fromProvider))
                        .switchedAt(LocalDateTime.now())
                        .build();
            }

            return SwitchDto.SwitchResult.builder()
                    .success(true)
                    .message("Already using " + targetProvider.getName() + ", refreshed settings")
                    .currentProvider(mapToProviderInfo(targetProvider))
                    .switchedAt(LocalDateTime.now())
                    .build();
        }

        // Backup is currently tied to current_provider_id (Claude). Skip for Codex to avoid FK confusion.
        if (tool == TargetTool.CLAUDE_CODE) {
            configService.createBackup(userId, config, BackupType.AUTO_BEFORE_SWITCH,
                    "Auto backup before switching to " + targetProvider.getName());
        }

        SwitchHistory history = SwitchHistory.builder()
                .user(user)
                .fromProvider(fromProvider)
                .toProvider(targetProvider)
                .switchType(switchType)
                .aiPrompt(aiPrompt)
                .clientInfo(clientInfo)
                .build();

        try {
            if (tool == TargetTool.CLAUDE_CODE) {
                config.setCurrentProvider(targetProvider);
            } else {
                config.setCurrentCodexProvider(targetProvider);
            }
            configRepository.save(config);

            apiKeyService.updateLastUsedAt(userId, providerCode, tool);

            writeSettingsForTool(userId, targetProvider, tool);

            history.setSuccess(true);
            switchHistoryRepository.save(history);

            log.info("Switched user {} ({}) from {} to {}", userId, tool,
                    fromProvider == null ? "<none>" : fromProvider.getCode(), providerCode);

            return SwitchDto.SwitchResult.builder()
                    .success(true)
                    .message("Successfully switched to " + targetProvider.getName())
                    .previousProvider(mapToProviderInfo(fromProvider))
                    .currentProvider(mapToProviderInfo(targetProvider))
                    .switchedAt(LocalDateTime.now())
                    .build();
        } catch (Exception e) {
            history.setSuccess(false);
            history.setErrorMessage(e.getMessage());
            switchHistoryRepository.save(history);

            log.error("Failed to switch user {} ({}) to {}: {}", userId, tool, providerCode, e.getMessage());

            return SwitchDto.SwitchResult.builder()
                    .success(false)
                    .message("Failed to switch: " + e.getMessage())
                    .currentProvider(mapToProviderInfo(fromProvider))
                    .switchedAt(LocalDateTime.now())
                    .build();
        }
    }

    @Transactional
    public SwitchDto.SwitchResult switchToProvider(Long userId, SwitchDto.SwitchRequest request, TargetTool tool) {
        return switchToProvider(userId, request.getProviderCode(), tool, SwitchType.MANUAL, null, request.getClientInfo());
    }

    private ModelProvider currentProviderForTool(UserConfig config, TargetTool tool) {
        return tool == TargetTool.CLAUDE_CODE ? config.getCurrentProvider() : config.getCurrentCodexProvider();
    }

    private void writeSettingsForTool(Long userId, ModelProvider provider, TargetTool tool) {
        if (tool == TargetTool.CLAUDE_CODE) {
            settingsWriterService.writeToSettings(userId, provider);
        } else {
            codexSettingsWriterService.writeToSettings(userId, provider);
        }
    }

    private ProviderDto.ProviderInfo mapToProviderInfo(ModelProvider provider) {
        if (provider == null) return null;
        return ProviderDto.ProviderInfo.builder()
                .id(provider.getId())
                .code(provider.getCode())
                .name(provider.getName())
                .description(provider.getDescription())
                .baseUrl(provider.getBaseUrl())
                .modelName(provider.getModelName())
                .modelNameSmall(provider.getModelNameSmall())
                .isBuiltin(provider.getIsBuiltin())
                .isActive(provider.getIsActive())
                .sortOrder(provider.getSortOrder())
                .iconUrl(provider.getIconUrl())
                .targetTool(provider.getTargetTool() != null ? provider.getTargetTool().name() : null)
                .wireApi(provider.getWireApi())
                .createdAt(provider.getCreatedAt())
                .build();
    }
}
