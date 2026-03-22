package com.paicoding.paiswitch.controller;

import com.paicoding.paiswitch.common.response.ApiResponse;
import com.paicoding.paiswitch.domain.dto.SkillDto;
import com.paicoding.paiswitch.service.SkillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Skills", description = "Claude Code skill management APIs")
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class SkillController {

    private final SkillService skillService;

    @Operation(summary = "List all local skills")
    @GetMapping
    public ApiResponse<SkillDto.SkillListResponse> listSkills() {
        return ApiResponse.success(skillService.listSkills());
    }

    @Operation(summary = "Get skill detail")
    @GetMapping("/{folderName}")
    public ApiResponse<SkillDto.SkillDetail> getSkillDetail(@PathVariable String folderName) {
        return ApiResponse.success(skillService.getSkillDetail(folderName));
    }

    @Operation(summary = "Rename a skill folder")
    @PostMapping("/{folderName}/rename")
    public ApiResponse<SkillDto.SkillSummary> renameSkill(
            @PathVariable String folderName,
            @Valid @RequestBody SkillDto.RenameRequest request) {
        return ApiResponse.success(skillService.renameSkill(folderName, request.getNewFolderName()));
    }

    @Operation(summary = "Move a skill to trash")
    @PostMapping("/{folderName}/trash")
    public ApiResponse<Void> moveToTrash(@PathVariable String folderName) {
        skillService.moveToTrash(folderName);
        return ApiResponse.success("Moved to trash", null);
    }

    @Operation(summary = "List trash entries")
    @GetMapping("/trash")
    public ApiResponse<List<SkillDto.TrashSkillEntry>> listTrash() {
        return ApiResponse.success(skillService.listTrash());
    }

    @Operation(summary = "Restore a skill from trash")
    @PostMapping("/trash/{trashEntry}/restore")
    public ApiResponse<SkillDto.SkillSummary> restoreFromTrash(@PathVariable String trashEntry) {
        return ApiResponse.success(skillService.restoreFromTrash(trashEntry));
    }
}
