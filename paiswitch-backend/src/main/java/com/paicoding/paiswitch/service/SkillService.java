package com.paicoding.paiswitch.service;

import com.paicoding.paiswitch.common.exception.BusinessException;
import com.paicoding.paiswitch.common.response.ResponseCode;
import com.paicoding.paiswitch.domain.dto.SkillDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Service
public class SkillService {

    private static final String SKILL_MD = "SKILL.md";
    private static final String TRASH_DIR_NAME = ".trash";
    private static final Pattern SKILL_NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final Pattern TRASH_ENTRY_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final DateTimeFormatter TRASH_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final ZoneId ZONE_ID = ZoneId.systemDefault();

    public SkillDto.SkillListResponse listSkills() {
        ensureSkillsRoot();

        List<SkillDto.SkillSummary> skills = listSkillDirectories(getSkillsRoot())
                .map(this::toSkillSummary)
                .sorted(Comparator.comparing(SkillDto.SkillSummary::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        int invalidCount = (int) skills.stream()
                .filter(skill -> !"valid".equals(skill.getStatus()))
                .count();

        return SkillDto.SkillListResponse.builder()
                .rootPath(getSkillsRoot().toString())
                .totalSkills(skills.size())
                .invalidSkills(invalidCount)
                .trashedSkills(listTrash().size())
                .skills(skills)
                .build();
    }

    public SkillDto.SkillDetail getSkillDetail(String folderName) {
        validateSkillName(folderName);
        Path skillDir = resolveActiveSkill(folderName);
        SkillMetadata metadata = readSkillMetadata(skillDir);

        return SkillDto.SkillDetail.builder()
                .folderName(metadata.folderName())
                .displayName(metadata.displayName())
                .absolutePath(skillDir.toString())
                .description(metadata.description())
                .status(metadata.status())
                .hasSkillMd(metadata.hasSkillMd())
                .hasLicense(metadata.hasLicense())
                .hasScripts(metadata.hasScripts())
                .hasReferences(metadata.hasReferences())
                .hasAssets(metadata.hasAssets())
                .fileCount(metadata.fileCount())
                .sizeBytes(metadata.sizeBytes())
                .updatedAt(metadata.updatedAt())
                .warnings(metadata.warnings())
                .skillMdContent(metadata.skillMdContent())
                .frontmatter(metadata.frontmatter())
                .files(listFiles(skillDir))
                .build();
    }

    public SkillDto.SkillSummary renameSkill(String folderName, String newFolderName) {
        validateSkillName(folderName);
        validateSkillName(newFolderName);

        Path source = resolveActiveSkill(folderName);
        Path target = getSkillsRoot().resolve(newFolderName);
        if (Files.exists(target)) {
            throw new BusinessException(ResponseCode.SKILL_ALREADY_EXISTS,
                    "Skill folder already exists: " + newFolderName);
        }

        try {
            Files.move(source, target);
        } catch (IOException e) {
            log.error("Failed to rename skill {} to {}", folderName, newFolderName, e);
            throw new BusinessException(ResponseCode.SKILL_OPERATION_FAILED, "Failed to rename skill");
        }

        return toSkillSummary(target);
    }

    public void moveToTrash(String folderName) {
        validateSkillName(folderName);

        Path source = resolveActiveSkill(folderName);
        Path trashDir = ensureTrashDir();
        String trashEntry = TRASH_TIMESTAMP_FORMATTER.format(LocalDateTime.now()) + "__" + folderName;
        Path target = trashDir.resolve(trashEntry);

        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailure) {
            try {
                Files.move(source, target);
            } catch (IOException e) {
                log.error("Failed to move skill {} to trash", folderName, e);
                throw new BusinessException(ResponseCode.SKILL_OPERATION_FAILED, "Failed to move skill to trash");
            }
        }
    }

    public List<SkillDto.TrashSkillEntry> listTrash() {
        Path trashDir = ensureTrashDir();

        try (Stream<Path> entries = Files.list(trashDir)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(path -> !isHiddenName(path.getFileName().toString()))
                    .map(this::toTrashEntry)
                    .sorted(Comparator.comparing(SkillDto.TrashSkillEntry::getDeletedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        } catch (IOException e) {
            log.error("Failed to list trash entries", e);
            throw new BusinessException(ResponseCode.SKILL_OPERATION_FAILED, "Failed to list trash");
        }
    }

    public SkillDto.SkillSummary restoreFromTrash(String trashEntry) {
        validateTrashEntry(trashEntry);
        Path source = resolveTrashEntry(trashEntry);
        ParsedTrashEntry parsedTrashEntry = parseTrashEntry(trashEntry);
        String restoredName = resolveRestoredFolderName(parsedTrashEntry.originalFolderName());
        Path target = getSkillsRoot().resolve(restoredName);

        try {
            Files.move(source, target);
        } catch (IOException e) {
            log.error("Failed to restore trash entry {}", trashEntry, e);
            throw new BusinessException(ResponseCode.SKILL_OPERATION_FAILED, "Failed to restore skill");
        }

        return toSkillSummary(target);
    }

    private Path getSkillsRoot() {
        String overridePath = System.getProperty("paiswitch.skills.root");
        if (overridePath != null && !overridePath.isBlank()) {
            return Paths.get(overridePath);
        }
        return Paths.get(System.getProperty("user.home"), ".claude", "skills");
    }

    private void ensureSkillsRoot() {
        Path root = getSkillsRoot();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.error("Failed to create skills root {}", root, e);
            throw new BusinessException(ResponseCode.SKILL_OPERATION_FAILED, "Failed to access skills directory");
        }
    }

    private Path ensureTrashDir() {
        ensureSkillsRoot();
        Path trashDir = getSkillsRoot().resolve(TRASH_DIR_NAME);
        try {
            Files.createDirectories(trashDir);
            return trashDir;
        } catch (IOException e) {
            log.error("Failed to create trash directory {}", trashDir, e);
            throw new BusinessException(ResponseCode.SKILL_OPERATION_FAILED, "Failed to access trash directory");
        }
    }

    private Stream<Path> listSkillDirectories(Path root) {
        try {
            return Files.list(root)
                    .filter(Files::isDirectory)
                    .filter(path -> !TRASH_DIR_NAME.equals(path.getFileName().toString()))
                    .filter(path -> !isHiddenName(path.getFileName().toString()));
        } catch (IOException e) {
            log.error("Failed to scan skills root {}", root, e);
            throw new BusinessException(ResponseCode.SKILL_OPERATION_FAILED, "Failed to read skills directory");
        }
    }

    private SkillDto.SkillSummary toSkillSummary(Path skillDir) {
        SkillMetadata metadata = readSkillMetadata(skillDir);

        return SkillDto.SkillSummary.builder()
                .folderName(metadata.folderName())
                .displayName(metadata.displayName())
                .absolutePath(skillDir.toString())
                .description(metadata.description())
                .status(metadata.status())
                .hasSkillMd(metadata.hasSkillMd())
                .hasLicense(metadata.hasLicense())
                .hasScripts(metadata.hasScripts())
                .hasReferences(metadata.hasReferences())
                .hasAssets(metadata.hasAssets())
                .fileCount(metadata.fileCount())
                .sizeBytes(metadata.sizeBytes())
                .updatedAt(metadata.updatedAt())
                .warnings(metadata.warnings())
                .build();
    }

    private SkillMetadata readSkillMetadata(Path skillDir) {
        String folderName = skillDir.getFileName().toString();
        Path skillMdPath = skillDir.resolve(SKILL_MD);
        Path scriptsDir = skillDir.resolve("scripts");
        Path referencesDir = skillDir.resolve("references");
        Path assetsDir = skillDir.resolve("assets");

        List<String> warnings = new ArrayList<>();
        boolean hasSkillMd = Files.isRegularFile(skillMdPath);
        boolean hasLicense = hasVisibleLicense(skillDir);
        boolean hasScripts = Files.isDirectory(scriptsDir);
        boolean hasReferences = Files.isDirectory(referencesDir);
        boolean hasAssets = Files.isDirectory(assetsDir);

        String skillMdContent = null;
        Map<String, String> frontmatter = new HashMap<>();
        if (hasSkillMd) {
            try {
                skillMdContent = Files.readString(skillMdPath);
                frontmatter = parseFrontmatter(skillMdContent, warnings);
            } catch (IOException e) {
                warnings.add("Failed to read SKILL.md");
            }
        } else {
            warnings.add("Missing SKILL.md");
        }

        FileStats stats = computeStats(skillDir);
        String displayName = firstNonBlank(frontmatter.get("name"), folderName);
        String description = frontmatter.get("description");
        String status = hasSkillMd ? "valid" : "invalid";

        return new SkillMetadata(
                folderName,
                displayName,
                description,
                status,
                hasSkillMd,
                hasLicense,
                hasScripts,
                hasReferences,
                hasAssets,
                stats.fileCount(),
                stats.sizeBytes(),
                stats.updatedAt(),
                List.copyOf(warnings),
                skillMdContent,
                Map.copyOf(frontmatter)
        );
    }

    private FileStats computeStats(Path skillDir) {
        long fileCount = 0;
        long sizeBytes = 0;
        Instant latest = Instant.EPOCH;

        try (Stream<Path> paths = Files.walk(skillDir, FileVisitOption.FOLLOW_LINKS)) {
            List<Path> collected = paths
                    .filter(path -> !path.equals(skillDir))
                    .filter(path -> !containsHiddenSegment(skillDir, path))
                    .toList();

            for (Path path : collected) {
                FileTime lastModifiedTime = Files.getLastModifiedTime(path);
                if (lastModifiedTime.toInstant().isAfter(latest)) {
                    latest = lastModifiedTime.toInstant();
                }
                if (Files.isRegularFile(path)) {
                    fileCount++;
                    sizeBytes += Files.size(path);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to compute stats for {}", skillDir, e);
        }

        LocalDateTime updatedAt = Instant.EPOCH.equals(latest)
                ? toLocalDateTime(safeLastModified(skillDir))
                : LocalDateTime.ofInstant(latest, ZONE_ID);

        return new FileStats(fileCount, sizeBytes, updatedAt);
    }

    private List<SkillDto.SkillFileEntry> listFiles(Path skillDir) {
        try (Stream<Path> paths = Files.walk(skillDir)) {
            return paths
                    .filter(path -> !path.equals(skillDir))
                    .filter(path -> !containsHiddenSegment(skillDir, path))
                    .sorted(Comparator.comparing(path -> skillDir.relativize(path).toString().toLowerCase(Locale.ROOT)))
                    .map(path -> SkillDto.SkillFileEntry.builder()
                            .relativePath(skillDir.relativize(path).toString())
                            .type(Files.isDirectory(path) ? "directory" : "file")
                            .sizeBytes(Files.isDirectory(path) ? 0L : safeSize(path))
                            .updatedAt(toLocalDateTime(safeLastModified(path)))
                            .build())
                    .toList();
        } catch (IOException e) {
            log.error("Failed to list files for {}", skillDir, e);
            throw new BusinessException(ResponseCode.SKILL_OPERATION_FAILED, "Failed to list skill files");
        }
    }

    private Map<String, String> parseFrontmatter(String content, List<String> warnings) {
        Map<String, String> frontmatter = new HashMap<>();
        if (!content.startsWith("---")) {
            warnings.add("Missing frontmatter block");
            return frontmatter;
        }

        int endIndex = content.indexOf("\n---", 3);
        if (endIndex < 0) {
            warnings.add("Unclosed frontmatter block");
            return frontmatter;
        }

        String yamlBlock = content.substring(3, endIndex).trim();
        if (yamlBlock.isEmpty()) {
            warnings.add("Empty frontmatter block");
            return frontmatter;
        }

        for (String line : yamlBlock.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colonIndex = trimmed.indexOf(':');
            if (colonIndex <= 0) {
                warnings.add("Skipped malformed frontmatter line: " + trimmed);
                continue;
            }

            String key = trimmed.substring(0, colonIndex).trim();
            String rawValue = trimmed.substring(colonIndex + 1).trim();
            String value = stripQuotes(rawValue);
            if (List.of("name", "description", "license", "compatibility").contains(key) && !value.isEmpty()) {
                frontmatter.put(key, value);
            }
        }

        return frontmatter;
    }

    private String stripQuotes(String value) {
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private boolean hasVisibleLicense(Path skillDir) {
        try (Stream<Path> entries = Files.list(skillDir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !isHiddenName(name))
                    .anyMatch(name -> name.toUpperCase(Locale.ROOT).startsWith("LICENSE"));
        } catch (IOException e) {
            return false;
        }
    }

    private Path resolveActiveSkill(String folderName) {
        ensureSkillsRoot();
        Path path = getSkillsRoot().resolve(folderName);
        if (!Files.isDirectory(path)) {
            throw new BusinessException(ResponseCode.SKILL_NOT_FOUND, "Skill not found: " + folderName);
        }
        return path;
    }

    private Path resolveTrashEntry(String trashEntry) {
        Path path = ensureTrashDir().resolve(trashEntry);
        if (!Files.isDirectory(path)) {
            throw new BusinessException(ResponseCode.TRASH_ENTRY_NOT_FOUND, "Trash entry not found: " + trashEntry);
        }
        return path;
    }

    private void validateSkillName(String folderName) {
        if (folderName == null || !SKILL_NAME_PATTERN.matcher(folderName).matches()) {
            throw new BusinessException(ResponseCode.INVALID_SKILL_NAME, "Invalid skill folder name");
        }
    }

    private void validateTrashEntry(String trashEntry) {
        if (trashEntry == null || !TRASH_ENTRY_PATTERN.matcher(trashEntry).matches()) {
            throw new BusinessException(ResponseCode.TRASH_ENTRY_NOT_FOUND, "Invalid trash entry");
        }
    }

    private SkillDto.TrashSkillEntry toTrashEntry(Path trashDir) {
        ParsedTrashEntry parsed = parseTrashEntry(trashDir.getFileName().toString());
        return SkillDto.TrashSkillEntry.builder()
                .trashEntry(trashDir.getFileName().toString())
                .originalFolderName(parsed.originalFolderName())
                .absolutePath(trashDir.toString())
                .deletedAt(parsed.deletedAt())
                .build();
    }

    private ParsedTrashEntry parseTrashEntry(String trashEntry) {
        int separator = trashEntry.indexOf("__");
        if (separator <= 0 || separator == trashEntry.length() - 2) {
            return new ParsedTrashEntry(trashEntry, toLocalDateTime(safeLastModified(ensureTrashDir().resolve(trashEntry))));
        }

        String timestampPart = trashEntry.substring(0, separator);
        String originalFolderName = trashEntry.substring(separator + 2);
        try {
            LocalDateTime deletedAt = LocalDateTime.parse(timestampPart, TRASH_TIMESTAMP_FORMATTER);
            return new ParsedTrashEntry(originalFolderName, deletedAt);
        } catch (DateTimeParseException e) {
            return new ParsedTrashEntry(originalFolderName,
                    toLocalDateTime(safeLastModified(ensureTrashDir().resolve(trashEntry))));
        }
    }

    private String resolveRestoredFolderName(String originalFolderName) {
        if (!Files.exists(getSkillsRoot().resolve(originalFolderName))) {
            return originalFolderName;
        }

        int suffix = 1;
        while (true) {
            String candidate = originalFolderName + "-restored-" + suffix;
            if (!Files.exists(getSkillsRoot().resolve(candidate))) {
                return candidate;
            }
            suffix++;
        }
    }

    private boolean containsHiddenSegment(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path segment : relative) {
            if (isHiddenName(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean isHiddenName(String name) {
        return name.startsWith(".");
    }

    private long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private FileTime safeLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return FileTime.from(Instant.EPOCH);
        }
    }

    private LocalDateTime toLocalDateTime(FileTime fileTime) {
        return LocalDateTime.ofInstant(fileTime.toInstant(), ZONE_ID);
    }

    private String firstNonBlank(String preferred, String fallback) {
        return Optional.ofNullable(preferred)
                .filter(value -> !value.isBlank())
                .orElse(fallback);
    }

    private record FileStats(long fileCount, long sizeBytes, LocalDateTime updatedAt) {
    }

    private record ParsedTrashEntry(String originalFolderName, LocalDateTime deletedAt) {
    }

    private record SkillMetadata(
            String folderName,
            String displayName,
            String description,
            String status,
            boolean hasSkillMd,
            boolean hasLicense,
            boolean hasScripts,
            boolean hasReferences,
            boolean hasAssets,
            long fileCount,
            long sizeBytes,
            LocalDateTime updatedAt,
            List<String> warnings,
            String skillMdContent,
            Map<String, String> frontmatter
    ) {
    }
}
