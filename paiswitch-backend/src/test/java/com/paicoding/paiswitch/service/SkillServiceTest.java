package com.paicoding.paiswitch.service;

import com.paicoding.paiswitch.common.exception.BusinessException;
import com.paicoding.paiswitch.domain.dto.SkillDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillServiceTest {

    @TempDir
    Path tempDir;

    private final SkillService skillService = new SkillService();

    @AfterEach
    void tearDown() {
        System.clearProperty("paiswitch.skills.root");
    }

    @Test
    void shouldListValidAndInvalidSkills() throws IOException {
        Path root = configureRoot();
        createSkill(root, "frontend-design", true, true, true, true);
        createSkill(root, "broken-skill", false, false, false, false);

        SkillDto.SkillListResponse response = skillService.listSkills();

        assertEquals(2, response.getTotalSkills());
        assertEquals(1, response.getInvalidSkills());
        assertEquals(0, response.getTrashedSkills());

        SkillDto.SkillSummary validSkill = response.getSkills().stream()
                .filter(skill -> skill.getFolderName().equals("frontend-design"))
                .findFirst()
                .orElseThrow();
        assertEquals("frontend-design", validSkill.getDisplayName());
        assertTrue(validSkill.getHasSkillMd());
        assertTrue(validSkill.getHasScripts());
        assertTrue(validSkill.getHasReferences());
        assertTrue(validSkill.getHasAssets());

        SkillDto.SkillSummary invalidSkill = response.getSkills().stream()
                .filter(skill -> skill.getFolderName().equals("broken-skill"))
                .findFirst()
                .orElseThrow();
        assertEquals("invalid", invalidSkill.getStatus());
        assertTrue(invalidSkill.getWarnings().contains("Missing SKILL.md"));
    }

    @Test
    void shouldReturnDetailWithoutFrontmatter() throws IOException {
        Path root = configureRoot();
        Path skillDir = root.resolve("docless-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "# Plain markdown\nNo frontmatter here.");

        SkillDto.SkillDetail detail = skillService.getSkillDetail("docless-skill");

        assertEquals("docless-skill", detail.getFolderName());
        assertTrue(detail.getWarnings().contains("Missing frontmatter block"));
        assertTrue(detail.getFiles().stream().anyMatch(file -> file.getRelativePath().equals("SKILL.md")));
    }

    @Test
    void shouldRenameSkillAndRejectInvalidNames() throws IOException {
        Path root = configureRoot();
        createSkill(root, "old-name", true, false, false, false);

        SkillDto.SkillSummary renamed = skillService.renameSkill("old-name", "new-name");

        assertEquals("new-name", renamed.getFolderName());
        assertTrue(Files.exists(root.resolve("new-name")));
        assertFalse(Files.exists(root.resolve("old-name")));

        assertThrows(BusinessException.class, () -> skillService.renameSkill("new-name", "Bad Name"));
    }

    @Test
    void shouldMoveSkillToTrashAndRestoreWithSuffixOnConflict() throws IOException {
        Path root = configureRoot();
        createSkill(root, "sample-skill", true, false, false, false);
        createSkill(root, "sample-skill-restored-1", true, false, false, false);

        skillService.moveToTrash("sample-skill");

        assertFalse(Files.exists(root.resolve("sample-skill")));
        List<SkillDto.TrashSkillEntry> trashEntries = skillService.listTrash();
        assertEquals(1, trashEntries.size());

        createSkill(root, "sample-skill", true, false, false, false);
        SkillDto.SkillSummary restored = skillService.restoreFromTrash(trashEntries.get(0).getTrashEntry());

        assertEquals("sample-skill-restored-2", restored.getFolderName());
        assertTrue(Files.exists(root.resolve("sample-skill-restored-2")));
        assertTrue(skillService.listTrash().isEmpty());
    }

    private Path configureRoot() throws IOException {
        Path root = tempDir.resolve(".claude").resolve("skills");
        Files.createDirectories(root);
        System.setProperty("paiswitch.skills.root", root.toString());
        return root;
    }

    private void createSkill(Path root, String folderName, boolean withSkillMd, boolean withScripts,
                             boolean withReferences, boolean withAssets) throws IOException {
        Path skillDir = root.resolve(folderName);
        Files.createDirectories(skillDir);

        if (withSkillMd) {
            Files.writeString(skillDir.resolve("SKILL.md"), """
                    ---
                    name: %s
                    description: A managed skill
                    license: MIT
                    ---

                    body
                    """.formatted(folderName));
        }

        Files.writeString(skillDir.resolve("LICENSE.txt"), "MIT");
        if (withScripts) {
            Files.createDirectories(skillDir.resolve("scripts"));
            Files.writeString(skillDir.resolve("scripts").resolve("run.sh"), "echo skill");
        }
        if (withReferences) {
            Files.createDirectories(skillDir.resolve("references"));
            Files.writeString(skillDir.resolve("references").resolve("guide.md"), "guide");
        }
        if (withAssets) {
            Files.createDirectories(skillDir.resolve("assets"));
            Files.writeString(skillDir.resolve("assets").resolve("icon.txt"), "icon");
        }
    }
}
