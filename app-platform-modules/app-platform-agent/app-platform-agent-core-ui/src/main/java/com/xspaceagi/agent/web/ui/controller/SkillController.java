package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.ConfigHistoryApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.application.SkillApplicationService;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.spec.utils.FileTypeUtils;
import com.xspaceagi.agent.web.ui.controller.util.SpaceObjectPermissionUtil;
import com.xspaceagi.agent.web.ui.dto.SkillAddDto;
import com.xspaceagi.agent.web.ui.dto.SkillCopyDto;
import com.xspaceagi.agent.web.ui.dto.SkillDto;
import com.xspaceagi.agent.web.ui.dto.SkillUpdateDto;
import com.xspaceagi.custompage.sdk.dto.CopyTypeEnum;
import com.xspaceagi.sandbox.SandboxRequestAttributes;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.SpaceUserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.SpacePermissionException;
import com.xspaceagi.system.spec.utils.I18nUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.http.MediaType;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "技能相关接口")
@RestController
@RequestMapping("/api/skill")
@Slf4j
public class SkillController {

    @Resource
    private SkillApplicationService skillApplicationService;
    @Resource
    private SpacePermissionService spacePermissionService;
    @Resource
    private SpaceApplicationService spaceApplicationService;
    @Resource
    private PublishApplicationService publishApplicationService;
    @Resource
    private ConfigHistoryApplicationService configHistoryApplicationService;

    @RequireResource(SKILL_CREATE)
    @Operation(summary = "新增技能")
    @PostMapping(value = "/add", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Long> add(@RequestBody @Valid SkillAddDto skillAddDto, HttpServletRequest request) {
        if (isSandboxSource(request)) {
            Long personalSpaceId = getPersonalSpaceId();
            skillAddDto.setSpaceId(personalSpaceId);
        }
        if (skillAddDto.getSpaceId() == null) {
            throw new IllegalArgumentException("Invalid spaceId");
        }
        if (skillAddDto.getName() == null) {
            throw new IllegalArgumentException("Skill name is required");
        }

        String name = skillAddDto.getName();
        if (!name.matches("^[\\u4e00-\\u9fa5A-Za-z0-9_-]+$")) {
            throw new IllegalArgumentException("Skill name may only contain letters, digits, underscore (_), and hyphen (-)");
        }

        spacePermissionService.checkSpaceUserPermission(skillAddDto.getSpaceId());

        SkillConfigDto skillConfigDto = new SkillConfigDto();
        BeanUtils.copyProperties(skillAddDto, skillConfigDto);

        // 如果原技能没有任何文件，把上传的文件和默认模板文件都加到 files 中
        if (CollectionUtils.isEmpty(skillConfigDto.getFiles())) {
            ReqResult<SkillDto> template = getTemplate();
            List<SkillFileDto> templateFiles = template.getData().getFiles();
            skillConfigDto.setFiles(templateFiles);
        }

        Long skillId = skillApplicationService.add(skillConfigDto);
        return ReqResult.success(skillId);
    }

    @RequireResource(SKILL_MODIFY)
    @Operation(summary = "修改技能")
    @PostMapping(value = "/update", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> update(@RequestBody SkillUpdateDto skillUpdateDto) {
        checkSkillPermission(skillUpdateDto.getId());

        if (skillUpdateDto.getName() != null) {
            if (!skillUpdateDto.getName().matches("^[\\u4e00-\\u9fa5A-Za-z0-9_-]+$")) {
                throw new IllegalArgumentException("Skill name may only contain letters, digits, underscore (_), and hyphen (-)");
            }
        }

        SkillConfigDto skillConfigDto = new SkillConfigDto();
        BeanUtils.copyProperties(skillUpdateDto, skillConfigDto);

        skillConfigDto.setModifiedId(RequestContext.get().getUserId());
        skillConfigDto.setModifiedName(RequestContext.get().getUserContext().getUserName());
        try {
            skillApplicationService.update(skillConfigDto, false);
        } catch (Exception e) {
            throw e;
        }
        return ReqResult.success();
    }

    @RequireResource(SKILL_MODIFY)
    @Operation(summary = "上传文件到技能")
    @PostMapping(value = "/upload-file", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> uploadFile(@RequestParam("file") MultipartFile file, @RequestParam("filePath") String filePath, @RequestParam("skillId") Long skillId) throws IOException {
        if (skillId == null) {
            throw new IllegalArgumentException("Invalid skillId");
        }

        // 检查单个文件大小，限制为 20M
        long maxSingleFileSize = 20L * 1024 * 1024;
        if (file.getSize() >= maxSingleFileSize) {
            throw new IllegalArgumentException("Skill package size must not exceed 20 MB");
        }

        // 检查技能权限
        SkillConfigDto exist = checkSkillPermission(skillId);

        // 计算现有文件的总大小
        long existingTotalSize = calculateTotalFileSize(exist.getFiles());

        // 检查新文件 + 原文件总大小，限制为 30M
        long maxTotalSize = 30L * 1024 * 1024;
        long newFileSize = file.getSize();
        if (existingTotalSize + newFileSize > maxTotalSize) {
            throw new IllegalArgumentException("Skill package size must not exceed 20 MB");
        }

        List<SkillFileDto> updateFiles = new ArrayList<>();

        // 如果原技能没有任何文件，把上传的文件和默认模板文件都加到 files 中
        if (CollectionUtils.isEmpty(exist.getFiles())) {
            ReqResult<SkillDto> template = getTemplate();
            List<SkillFileDto> templateFiles = template.getData().getFiles();
            if (!CollectionUtils.isEmpty(templateFiles)) {
                for (SkillFileDto templateFile : templateFiles) {
                    templateFile.setOperation("create");
                    updateFiles.add(templateFile);
                }
            }
        }

        SkillFileDto uploadFileDto = skillApplicationService.processUploadFile(file, filePath);
        uploadFileDto.setOperation("create");
        updateFiles.add(uploadFileDto);

        SkillConfigDto skillConfigDto = new SkillConfigDto();
        skillConfigDto.setId(skillId);
        skillConfigDto.setFiles(updateFiles);
        skillConfigDto.setModifiedId(RequestContext.get().getUserId());
        skillConfigDto.setModifiedName(RequestContext.get().getUserContext().getUserName());

        skillApplicationService.update(skillConfigDto, false);
        return ReqResult.success();
    }

    @RequireResource(SKILL_MODIFY)
    @Operation(summary = "批量上传文件到技能")
    @PostMapping(value = "/upload-files", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> uploadFiles(@RequestParam("files") List<MultipartFile> files, @RequestParam("filePaths") List<String> filePaths, @RequestParam("skillId") Long skillId) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Please select a file to upload");
        }
        if (filePaths == null || filePaths.isEmpty() || filePaths.size() != files.size()) {
            throw new IllegalArgumentException("filePaths and files count mismatch");
        }
        if (skillId == null) {
            throw new IllegalArgumentException("Invalid skillId");
        }

        // 检查单个文件大小，限制为 20M
        long maxSingleFileSize = 20L * 1024 * 1024;
        long newFilesTotalSize = 0L;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            if (file.getSize() >= maxSingleFileSize) {
                throw new IllegalArgumentException("Skill package size must not exceed 20 MB");
            }
            newFilesTotalSize += file.getSize();
        }

        // 检查技能权限
        SkillConfigDto exist = checkSkillPermission(skillId);

        // 计算现有文件的总大小
        long existingTotalSize = calculateTotalFileSize(exist.getFiles());

        // 检查新文件 + 原文件总大小，限制为 30M
        long maxTotalSize = 30L * 1024 * 1024;
        if (existingTotalSize + newFilesTotalSize > maxTotalSize) {
            throw new IllegalArgumentException("Skill package size must not exceed 20 MB");
        }

        List<SkillFileDto> updateFiles = new ArrayList<>();

        // 如果原技能没有任何文件，把默认模板文件加到 files 中
        if (CollectionUtils.isEmpty(exist.getFiles())) {
            ReqResult<SkillDto> template = getTemplate();
            List<SkillFileDto> templateFiles = template.getData().getFiles();
            if (!CollectionUtils.isEmpty(templateFiles)) {
                for (SkillFileDto templateFile : templateFiles) {
                    templateFile.setOperation("create");
                    updateFiles.add(templateFile);
                }
            }
        }

        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String filePath = filePaths.get(i);

            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("Empty file found; fix and upload again");
            }
            if (filePath == null || filePath.trim().isEmpty()) {
                throw new IllegalArgumentException("Invalid filePath found; fix and upload again");
            }

            SkillFileDto uploadFileDto = skillApplicationService.processUploadFile(file, filePath);
            uploadFileDto.setOperation("create");
            updateFiles.add(uploadFileDto);
        }

        SkillConfigDto skillConfigDto = new SkillConfigDto();
        skillConfigDto.setId(skillId);
        skillConfigDto.setFiles(updateFiles);
        skillConfigDto.setModifiedId(RequestContext.get().getUserId());
        skillConfigDto.setModifiedName(RequestContext.get().getUserContext().getUserName());

        skillApplicationService.update(skillConfigDto, false);
        return ReqResult.success();
    }

    @RequireResource(SKILL_DELETE)
    @Operation(summary = "删除技能")
    @PostMapping(path = "/delete/{skillId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> delete(@PathVariable Long skillId) {
        checkSkillPermission(skillId);
        skillApplicationService.delete(skillId);
        return ReqResult.success();
    }

    @RequireResource(SKILL_QUERY_DETAIL)
    @Operation(summary = "查询技能详情")
    @GetMapping(path = "/{skillId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<SkillDto> getSkill(@PathVariable Long skillId) {
        SkillConfigDto skillConfigDto = checkSkillPermission(skillId);
        SkillDto skillDto = new SkillDto();
        BeanUtils.copyProperties(skillConfigDto, skillDto);
        skillDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(skillConfigDto.getIcon(), skillConfigDto.getName()));
        SpaceUserDto spaceUserDto = spaceApplicationService.querySpaceUser(skillConfigDto.getSpaceId(), RequestContext.get().getUserId());
        skillDto.setPermissions(SpaceObjectPermissionUtil.generatePermissionList(spaceUserDto, skillConfigDto.getCreatorId()).stream().map(permission -> permission.name()).collect(Collectors.toList()));
        return ReqResult.success(skillDto);
    }

    @RequireResource(value = SKILL_QUERY_LIST)
    @Operation(summary = "查询技能列表")
    @GetMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<List<SkillDto>> list(SkillQueryDto queryDto, HttpServletRequest request) {
        if (isSandboxSource(request)) {
            Long personalSpaceId = getPersonalSpaceId();
            queryDto.setSpaceId(personalSpaceId);
        }
        if (queryDto.getSpaceId() == null) {
            throw new IllegalArgumentException("Invalid spaceId");
        }
        spacePermissionService.checkSpaceUserPermission(queryDto.getSpaceId());

        List<SkillConfigDto> skills = skillApplicationService.queryList(queryDto);
        return ReqResult.success(skills.stream().map(skill -> {
            SkillDto skillDto = new SkillDto();
            BeanUtils.copyProperties(skill, skillDto);
            skillDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(skill.getIcon(), skill.getName(), "skill"));
            return skillDto;
        }).collect(Collectors.toList()));
    }

    @RequireResource(SKILL_EXPORT)
    @Operation(summary = "导出技能")
    @GetMapping(path = "/export/{skillId}", produces = "application/octet-stream")
    public byte[] export(@PathVariable Long skillId, HttpServletResponse response) {
        checkSkillPermission(skillId);

        SkillExportResultDto exportResult = skillApplicationService.exportSkill(skillId);

        // 设置响应头
        response.setHeader("Content-Disposition", "attachment;filename=" + URLEncoder.encode(exportResult.getFileName(), Charset.forName("UTF-8")));
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");

        return exportResult.getData();
    }

    @RequireResource(SKILL_IMPORT)
    @Operation(summary = "导入技能")
    @PostMapping(value = "/import", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Long> importSkill(@RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "targetSkillId", required = false) Long targetSkillId,
                                         @RequestParam(value = "targetSpaceId", required = false) Long targetSpaceId,
                                         HttpServletRequest request) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Please select a file to upload");
        }
        // 检查文件大小，限制为 20M
        long maxSize = 20L * 1024 * 1024;
        if (file.getSize() > maxSize) {
            throw new IllegalArgumentException("Import file must be smaller than 20 MB");
        }
        if (isSandboxSource(request)) {
            Long personalSpaceId = getPersonalSpaceId();
            targetSpaceId = personalSpaceId;
            // sandbox 场景下，targetSpaceId 由个人空间决定，需要校验权限
            spacePermissionService.checkSpaceUserPermission(targetSpaceId);
        }

        SkillConfigDto existSkill = null;
        if (targetSkillId != null) {
            existSkill = checkSkillPermission(targetSkillId);
        } else {
            if (targetSpaceId == null) {
                throw new IllegalArgumentException("Please select a target space");
            }
            spacePermissionService.checkSpaceUserPermission(targetSpaceId);
        }

        Long resultId = skillApplicationService.importSkill(file, existSkill, targetSpaceId);
        return ReqResult.success(resultId);
    }

    @RequireResource(SKILL_COPY_TO_SPACE)
    @Operation(summary = "复制技能")
    @PostMapping(value = "/copy", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Long> copy(@RequestBody SkillCopyDto skillCopyDto) {
        Long skillId = skillCopyDto.getSkillId();
        Long targetSpaceId = skillCopyDto.getTargetSpaceId();
        CopyTypeEnum copyType = skillCopyDto.getCopyType();

        if (skillId == null) {
            throw new IllegalArgumentException("Invalid skillId");
        }
        if (targetSpaceId == null) {
            throw new IllegalArgumentException("Invalid targetSpaceId");
        }
        if (copyType == null) {
            throw new IllegalArgumentException("Invalid copyType");
        }
        //校验目标空间权限
        spacePermissionService.checkSpaceUserPermission(targetSpaceId);

        SkillConfigDto skillConfigDto = skillApplicationService.queryById(skillId, true);
        if (skillConfigDto == null) {
            throw new IllegalArgumentException("Skill does not exist");
        }

        if (copyType == CopyTypeEnum.SQUARE) {
            //广场复制
            //校验技能复制权限
            PublishedPermissionDto permissionDto = publishApplicationService.hasPermission(Published.TargetType.Skill, skillId);
            if (!permissionDto.isCopy()) {
                throw new SpacePermissionException(I18nUtil.systemMessage("Backend.Skill.CopyPermissionDenied"));
            }
        } else {
            //开发复制
            //校验源空间权限
            spacePermissionService.checkSpaceUserPermission(skillConfigDto.getSpaceId());
        }

        Long id = skillApplicationService.copySkill(skillConfigDto, targetSpaceId);
        return ReqResult.success(id);
    }

    @RequireResource(SKILL_QUERY_DETAIL)
    @Operation(summary = "查询技能历史配置信息接口")
    @RequestMapping(path = "/config/history/list/{skillId}", method = RequestMethod.GET)
    public ReqResult<List<ConfigHistoryDto>> historyList(@PathVariable Long skillId) {
        checkSkillPermission(skillId);
        List<ConfigHistoryDto> historyList = configHistoryApplicationService.queryConfigHistoryList(Published.TargetType.Skill, skillId);
        return ReqResult.success(historyList);
    }

    @RequireResource(SKILL_QUERY_DETAIL)
    @Operation(summary = "查询技能模板")
    @GetMapping(value = "/template", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<SkillDto> getTemplate() {
        try {
            InputStream inputStream = SkillController.class.getClassLoader().getResourceAsStream("skill-template.json");
            if (inputStream == null) {
                log.error("Cannot find skill template file: skill-template.json");
                throw new IllegalArgumentException("Skill template file does not exist");
            }

            SkillConfigDto skillConfigDto = skillApplicationService.getSkillTemplate(inputStream);
            SkillDto skillDto = new SkillDto();
            BeanUtils.copyProperties(skillConfigDto, skillDto);
            return ReqResult.success(skillDto);
        } catch (Exception e) {
            log.error("Failed to read skill template", e);
            throw e;
        }
    }

    //检查技能权限
    private SkillConfigDto checkSkillPermission(Long skillId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Invalid skillId");
        }
        SkillConfigDto skillDto = skillApplicationService.queryById(skillId, true);
        if (skillDto == null) {
            throw new IllegalArgumentException("Skill does not exist");
        }
        spacePermissionService.checkSpaceUserPermission(skillDto.getSpaceId());
        return skillDto;
    }

    private boolean isSandboxSource(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        Object src = request.getAttribute(SandboxRequestAttributes.REQUEST_SOURCE);
        return SandboxRequestAttributes.SOURCE_SANDBOX.equals(src);
    }

    private Long getPersonalSpaceId() {
        Long userId = RequestContext.get().getUserId();
        List<SpaceDto> spaceDtos = spaceApplicationService.queryListByUserId(userId);
        SpaceDto personalSpace = spaceDtos.stream()
                .filter(spaceDto -> spaceDto.getType() == Space.Type.Personal)
                .findFirst()
                .orElse(null);
        if (personalSpace == null) {
            throw new IllegalArgumentException("User has no personal space");
        }
        return personalSpace.getId();
    }

    /**
     * 计算文件列表的总大小
     * 对于文本文件，直接计算字符串的字节大小
     * 对于二进制文件（base64编码），解码后计算原始字节大小
     */
    private long calculateTotalFileSize(List<SkillFileDto> files) {
        if (CollectionUtils.isEmpty(files)) {
            return 0L;
        }

        long totalSize = 0L;
        for (SkillFileDto file : files) {
            if (file == null || file.getIsDir() != null && file.getIsDir()) {
                // 跳过目录
                continue;
            }

            String contents = file.getContents();
            if (contents == null || contents.isBlank()) {
                continue;
            }

            String fileName = file.getName();
            if (fileName == null || fileName.isBlank()) {
                continue;
            }

            // 如果是文本文件，直接计算字符串的字节大小
            if (FileTypeUtils.isTextFile(fileName)) {
                totalSize += contents.getBytes(StandardCharsets.UTF_8).length;
            } else {
                // 非文本文件（二进制），尝试解码 base64
                try {
                    byte[] decodedBytes = Base64.getDecoder().decode(contents);
                    totalSize += decodedBytes.length;
                } catch (IllegalArgumentException e) {
                    // 如果不是有效的 base64，则按文本处理（兼容旧数据）
                    totalSize += contents.getBytes(StandardCharsets.UTF_8).length;
                }
            }
        }

        return totalSize;
    }

}