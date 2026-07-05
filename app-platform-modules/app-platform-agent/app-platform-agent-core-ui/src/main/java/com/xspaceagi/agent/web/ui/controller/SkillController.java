package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.recommend.TargetRecommendResponse;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.TargetRecommend;
import com.xspaceagi.agent.core.spec.enums.UsageScenarioEnum;
import com.xspaceagi.agent.core.spec.utils.FileTypeUtils;
import com.xspaceagi.agent.web.ui.controller.util.SpaceObjectPermissionUtil;
import com.xspaceagi.agent.web.ui.dto.SkillAddDto;
import com.xspaceagi.agent.web.ui.dto.SkillCopyDto;
import com.xspaceagi.agent.web.ui.dto.SkillDto;
import com.xspaceagi.agent.web.ui.dto.SkillUpdateDto;
import com.xspaceagi.custompage.sdk.dto.CopyTypeEnum;
import com.xspaceagi.sandbox.SandboxUtils;
import com.xspaceagi.system.application.dto.SpaceUserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
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
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.xspaceagi.agent.core.application.service.SkillApplicationServiceImpl.MAX_SINGLE_FILE_SIZE;
import static com.xspaceagi.agent.core.application.service.SkillApplicationServiceImpl.MAX_SKILL_FILE_SIZE;
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
    @Resource
    private AgentWorkspaceApplicationService agentWorkspaceApplicationService;
    @Resource
    private ConversationApplicationService conversationApplicationService;
    @Resource
    private RecommendApplicationService recommendApplicationService;

    @RequireResource(SKILL_CREATE)
    @Operation(summary = "新增技能")
    @PostMapping(value = "/add", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Long> add(@RequestBody @Valid SkillAddDto skillAddDto, HttpServletRequest request) {
        if (SandboxUtils.isSandboxRequest(request)) {
            if (skillAddDto.getSpaceId() == null) {
                Long personalSpaceId = spaceApplicationService.getPersonalSpaceId(RequestContext.get().getUserId());
                skillAddDto.setSpaceId(personalSpaceId);
            }
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
        skillConfigDto.setExt(convertExtArray(skillAddDto.getUsageScenarios(), true));

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
    public ReqResult<Void> update(@RequestBody @Valid SkillUpdateDto skillUpdateDto) {
        SkillConfigDto existSkill = checkSkillPermission(skillUpdateDto.getId());

        // 沙盒技能不允许修改文件
        if (isSandboxSkill(existSkill) && !CollectionUtils.isEmpty(skillUpdateDto.getFiles())) {
            throw new IllegalArgumentException("Cannot modify files of sandbox-developed skill");
        }

        if (skillUpdateDto.getName() != null) {
            if (!skillUpdateDto.getName().matches("^[\\u4e00-\\u9fa5A-Za-z0-9_-]+$")) {
                throw new IllegalArgumentException("Skill name may only contain letters, digits, underscore (_), and hyphen (-)");
            }
        }

        SkillConfigDto skillConfigDto = new SkillConfigDto();
        BeanUtils.copyProperties(skillUpdateDto, skillConfigDto);
        if (skillUpdateDto.getUsageScenarios() != null) {
            skillConfigDto.setExt(convertExtArray(skillUpdateDto.getUsageScenarios(), false));
        }

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

        // 检查单个文件大小
        if (file.getSize() >= MAX_SINGLE_FILE_SIZE) {
            throw new IllegalArgumentException("Skill package size must not exceed 80 MB");
        }

        // 检查技能权限
        SkillConfigDto exist = checkSkillPermission(skillId);

        // 沙盒技能不支持上传文件
        if (isSandboxSkill(exist)) {
            throw new IllegalArgumentException("Cannot upload files to sandbox-developed skill");
        }

        // 计算现有文件的总大小
        long existingTotalSize = calculateTotalFileSize(exist.getFiles());

        // 检查新文件 + 原文件总大小
        long newFileSize = file.getSize();
        if (existingTotalSize + newFileSize > MAX_SKILL_FILE_SIZE) {
            throw new IllegalArgumentException("Skill package size must not exceed 80 MB");
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

        SkillFileDto uploadFileDto = skillApplicationService.processUploadFile(file, filePath, skillId);
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

        // 检查单个文件大小
        long newFilesTotalSize = 0L;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            if (file.getSize() >= MAX_SINGLE_FILE_SIZE) {
                throw new IllegalArgumentException("Skill package size must not exceed 80 MB");
            }
            newFilesTotalSize += file.getSize();
        }

        // 检查技能权限
        SkillConfigDto exist = checkSkillPermission(skillId);

        // 沙盒技能不支持上传文件
        if (isSandboxSkill(exist)) {
            throw new IllegalArgumentException("Cannot upload files to sandbox-developed skill");
        }

        // 计算现有文件的总大小
        long existingTotalSize = calculateTotalFileSize(exist.getFiles());

        // 检查新文件 + 原文件总大小
        if (existingTotalSize + newFilesTotalSize > MAX_SKILL_FILE_SIZE) {
            throw new IllegalArgumentException("Skill package size must not exceed 80 MB");
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

            if (filePath == null || filePath.trim().isEmpty()) {
                throw new IllegalArgumentException("Invalid filePath found; fix and upload again");
            }
            boolean isDir = filePath.endsWith("/");
            if (file == null || file.isEmpty()) {
                if (!isDir) {
                    throw new IllegalArgumentException("Empty file found; fix and upload again");
                }
                SkillFileDto dirDto = new SkillFileDto();
                dirDto.setName(filePath.substring(0, filePath.length() - 1));
                dirDto.setIsDir(true);
                dirDto.setOperation("create");
                updateFiles.add(dirDto);
                continue;
            }
            SkillFileDto uploadFileDto = skillApplicationService.processUploadFile(file, filePath, skillId);
            uploadFileDto.setOperation("create");
            uploadFileDto.setIsDir(false);
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
        SkillConfigDto exist = checkSkillPermission(skillId);

        // 沙盒技能需要额外清理沙箱工作空间
        if (isSandboxSkill(exist)) {
            try {
                agentWorkspaceApplicationService.deleteWorkspace(exist.getCreatorId(), exist.getDevAgentConversationId());
            } catch (Exception e) {
                log.warn("Failed to delete sandbox workspace for skill, skillId={}, cId={}", skillId, exist.getDevAgentConversationId(), e);
            }
        }

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
        skillDto.setUsageScenarios(parseUsageScenarios(skillConfigDto.getExt()));
        skillDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(skillConfigDto.getIcon(), skillConfigDto.getName()));
        SpaceUserDto spaceUserDto = spaceApplicationService.querySpaceUser(skillConfigDto.getSpaceId(), RequestContext.get().getUserId());
        skillDto.setPermissions(SpaceObjectPermissionUtil.generatePermissionList(spaceUserDto, skillConfigDto.getCreatorId()).stream().map(permission -> permission.name()).collect(Collectors.toList()));
        return ReqResult.success(skillDto);
    }

    @RequireResource(value = SKILL_QUERY_LIST)
    @Operation(summary = "查询技能列表")
    @GetMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<List<SkillDto>> list(SkillQueryDto queryDto, HttpServletRequest request) {
        if (SandboxUtils.isSandboxRequest(request)) {
            if (queryDto.getSpaceId() == null) {
                Long personalSpaceId = spaceApplicationService.getPersonalSpaceId(RequestContext.get().getUserId());
                queryDto.setSpaceId(personalSpaceId);
            }
        }
        if (queryDto.getSpaceId() == null) {
            throw new IllegalArgumentException("Invalid spaceId");
        }
        spacePermissionService.checkSpaceUserPermission(queryDto.getSpaceId());

        List<SkillConfigDto> skills = skillApplicationService.queryList(queryDto);
        return ReqResult.success(skills.stream().map(skill -> {
            SkillDto skillDto = new SkillDto();
            BeanUtils.copyProperties(skill, skillDto);
            skillDto.setUsageScenarios(parseUsageScenarios(skill.getExt()));
            skillDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(skill.getIcon(), skill.getName(), "skill"));
            return skillDto;
        }).collect(Collectors.toList()));
    }

    @RequireResource(SKILL_EXPORT)
    @Operation(summary = "导出技能")
    @GetMapping(path = "/export/{skillId}", produces = "application/octet-stream")
    public byte[] export(@PathVariable Long skillId, HttpServletResponse response) {
        SkillConfigDto exist = checkSkillPermission(skillId);

        SkillExportResultDto exportResult;
        if (isSandboxSkill(exist)) {
            // 沙盒技能从沙箱导出
            exportResult = agentWorkspaceApplicationService.exportSkillFromSandbox(exist.getCreatorId(), exist.getDevAgentConversationId(), exist.getName());
        } else {
            exportResult = skillApplicationService.exportSkill(skillId);
        }

        // 设置响应头
        response.setHeader("Content-Disposition", "attachment;filename=" + URLEncoder.encode(exportResult.getFileName(), Charset.forName("UTF-8")));
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");

        return exportResult.getData();
    }

    @RequireResource(SKILL_IMPORT)
    @Operation(summary = "导入技能")
    @PostMapping(value = "/import", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Long> importSkill(@RequestParam(value = "url", required = false) String url,
                                       @RequestParam(value = "file", required = false) MultipartFile file,
                                       @RequestParam(value = "targetSkillId", required = false) Long targetSkillId,
                                       @RequestParam(value = "targetSpaceId", required = false) Long targetSpaceId,
                                       @RequestParam(value = "usageScenarios", required = false) List<UsageScenarioEnum> usageScenarios,
                                       HttpServletRequest request) throws IOException {
        if (SandboxUtils.isSandboxRequest(request)) {
            if (targetSpaceId == null) {
                targetSpaceId = spaceApplicationService.getPersonalSpaceId(RequestContext.get().getUserId());
            }
        }

        if (targetSkillId != null) {
            SkillConfigDto existSkill = checkSkillPermission(targetSkillId);
            // 沙盒技能不支持导入
            if (isSandboxSkill(existSkill)) {
                throw new IllegalArgumentException("Cannot import to sandbox-developed skill");
            }
        } else {
            if (targetSpaceId == null) {
                throw new IllegalArgumentException("Please select a target space");
            }
            spacePermissionService.checkSpaceUserPermission(targetSpaceId);
        }

        Long resultId = skillApplicationService.importSkill(url, file, targetSkillId, targetSpaceId, usageScenarios);
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

        // 沙盒技能复制：需要为新技能创建开发会话，并复制沙箱工作空间
        if (isSandboxSkill(skillConfigDto)) {
            try {
                TargetRecommendResponse recommendResponse = recommendApplicationService.list(TargetRecommend.RecType.ChatBoxNav.name(), TargetRecommend.TargetType.Agent.name()).stream().filter(targetRecommendResponse -> targetRecommendResponse.getFunctionType().equals(TargetRecommend.FunctionType.SkillDev.name())).findFirst().orElse(null);
                if (recommendResponse == null) {
                    throw new IllegalArgumentException("Develop agent not config");
                }

                Long currentUserId = RequestContext.get().getUserId();

                // 创建开发会话（新技能的 creatorId 是当前用户）
                Long newCId = conversationApplicationService.createConversationForProjectDevelopment(
                        RequestContext.get().getTenantId(),
                        currentUserId,
                        targetSpaceId,
                        recommendResponse.getTargetId(),
                        Published.TargetType.Skill.name(),
                        id
                ).getId();

                // 更新新技能的 devAgentConversationId
                SkillConfigDto updateDto = new SkillConfigDto();
                updateDto.setId(id);
                updateDto.setDevAgentConversationId(newCId);
                skillApplicationService.update(updateDto, false);

                // 复制源沙箱工作空间到新工作空间，并初始化 git
                agentWorkspaceApplicationService.copySandboxWorkspace(
                        skillConfigDto.getCreatorId(),
                        skillConfigDto.getDevAgentConversationId(),
                        currentUserId,
                        newCId
                );
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                log.error("Failed to setup sandbox workspace for copied skill, skillId={}", id, e);
                throw new RuntimeException("Failed to setup sandbox workspace for copied skill", e);
            }
        }

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
        SkillConfigDto skillConfigDto = skillApplicationService.getSkillTemplate();
        SkillDto skillDto = new SkillDto();
        BeanUtils.copyProperties(skillConfigDto, skillDto);
        skillDto.setUsageScenarios(parseUsageScenarios(skillConfigDto.getExt()));
        return ReqResult.success(skillDto);
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

    private SkillExtDto convertExtArray(List<UsageScenarioEnum> extArray, boolean useDefaultWhenEmpty) {
        if (extArray == null || extArray.isEmpty()) {
            if (!useDefaultWhenEmpty) {
                return null;
            }
            SkillExtDto defaultExt = new SkillExtDto();
            defaultExt.setSupportTaskAgent(1);
            defaultExt.setSupportPageApp(0);
            return defaultExt;
        }
        SkillExtDto ext = new SkillExtDto();
        ext.setSupportTaskAgent(extArray.contains(UsageScenarioEnum.TaskAgent) ? 1 : 0);
        ext.setSupportPageApp(extArray.contains(UsageScenarioEnum.PageApp) ? 1 : 0);
        return ext;
    }

    private List<UsageScenarioEnum> parseUsageScenarios(Object ext) {
        List<UsageScenarioEnum> usageScenarios = new ArrayList<>();
        if (ext == null) {
            usageScenarios.add(UsageScenarioEnum.TaskAgent);
            return usageScenarios;
        }
        if (ext instanceof SkillExtDto skillExtDto) {
            if (Integer.valueOf(1).equals(skillExtDto.getSupportTaskAgent())) {
                usageScenarios.add(UsageScenarioEnum.TaskAgent);
            }
            if (Integer.valueOf(1).equals(skillExtDto.getSupportPageApp())) {
                usageScenarios.add(UsageScenarioEnum.PageApp);
            }
            return usageScenarios;
        }
        if (!(ext instanceof java.util.Map<?, ?> extMap)) {
            return usageScenarios;
        }
        if (Objects.equals(parseExtInt(extMap.get("supportTaskAgent")), 1)) {
            usageScenarios.add(UsageScenarioEnum.TaskAgent);
        }
        if (Objects.equals(parseExtInt(extMap.get("supportPageApp")), 1)) {
            usageScenarios.add(UsageScenarioEnum.PageApp);
        }
        return usageScenarios;
    }

    private Integer parseExtInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isSandboxSkill(SkillConfigDto skill) {
        return skill != null && skill.getDevAgentConversationId() != null;
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