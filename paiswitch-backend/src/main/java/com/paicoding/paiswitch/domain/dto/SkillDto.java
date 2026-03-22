package com.paicoding.paiswitch.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class SkillDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SkillSummary {
        private String folderName;
        private String displayName;
        private String absolutePath;
        private String description;
        private String status;
        private Boolean hasSkillMd;
        private Boolean hasLicense;
        private Boolean hasScripts;
        private Boolean hasReferences;
        private Boolean hasAssets;
        private Long fileCount;
        private Long sizeBytes;
        private LocalDateTime updatedAt;
        private List<String> warnings;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SkillDetail {
        private String folderName;
        private String displayName;
        private String absolutePath;
        private String description;
        private String status;
        private Boolean hasSkillMd;
        private Boolean hasLicense;
        private Boolean hasScripts;
        private Boolean hasReferences;
        private Boolean hasAssets;
        private Long fileCount;
        private Long sizeBytes;
        private LocalDateTime updatedAt;
        private List<String> warnings;
        private String skillMdContent;
        private Map<String, String> frontmatter;
        private List<SkillFileEntry> files;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SkillFileEntry {
        private String relativePath;
        private String type;
        private Long sizeBytes;
        private LocalDateTime updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TrashSkillEntry {
        private String trashEntry;
        private String originalFolderName;
        private String absolutePath;
        private LocalDateTime deletedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SkillListResponse {
        private String rootPath;
        private Integer totalSkills;
        private Integer invalidSkills;
        private Integer trashedSkills;
        private List<SkillSummary> skills;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RenameRequest {
        @NotBlank(message = "New folder name is required")
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,63}$", message = "Folder name must be a lowercase slug up to 64 characters")
        private String newFolderName;
    }
}
