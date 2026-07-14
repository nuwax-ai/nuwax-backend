package com.xspaceagi.agent.core.application.service;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.application.AgentWorkspaceApplicationService;
import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.application.SkillApplicationService;
import com.xspaceagi.agent.core.adapter.constant.SkillFileFormatConstants;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.Conversation;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.SkillConfig;
import com.xspaceagi.agent.core.domain.service.AgentDomainService;
import com.xspaceagi.agent.core.domain.service.ConversationDomainService;
import com.xspaceagi.agent.core.domain.service.PublishDomainService;
import com.xspaceagi.agent.core.domain.service.SkillDomainService;
import com.xspaceagi.agent.core.infra.rpc.ComputerFileClient;
import com.xspaceagi.agent.core.infra.rpc.GitRpcClient;
import com.xspaceagi.agent.core.infra.rpc.WorkspaceRpcClient;
import com.xspaceagi.agent.core.adapter.util.SkillNameUtil;
import com.xspaceagi.agent.core.spec.utils.FileTypeUtils;
import com.xspaceagi.agent.core.spec.utils.MarkdownExtractUtil;
import com.xspaceagi.agent.core.spec.utils.UrlFile;
import com.xspaceagi.file.application.service.FileManagementService;
import com.xspaceagi.file.domain.model.FileRecordDomain;
import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.file.InMemoryMultipartFile;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.springframework.core.io.ClassPathResource;

@Slf4j
@Service
public class AgentWorkspaceApplicationServiceImpl implements AgentWorkspaceApplicationService {
    @Resource
    private WorkspaceRpcClient workspaceRpcClient;
    @Resource
    private SkillDomainService skillDomainService;
    @Resource
    private PublishApplicationService publishApplicationService;
    @Resource
    private PublishDomainService publishDomainService;
    @Resource
    private IFileAccessService iFileAccessService;
    @Resource
    private ComputerFileClient computerFileClient;
    @Resource
    private FileManagementService fileManagementService;
    @Resource
    private AgentDomainService agentDomainService;
    @Resource
    private AgentApplicationService agentApplicationService;
    @Resource
    private ConversationDomainService conversationDomainService;
    @Resource
    private SkillApplicationService skillApplicationService;
    @Resource
    private GitRpcClient gitRpcClient;

    @Override
    public void createWorkspace(CreateWorkspaceDto createWorkspaceDto) {
        log.info("[createWorkspace] userId={} cId={} skillIds={}, subagents.size={}  创建工作空间开始",
                createWorkspaceDto.getUserId(), createWorkspaceDto.getCId(), createWorkspaceDto.getSkillIds(), CollectionUtils.size(createWorkspaceDto.getSubagents()));
        Long userId = createWorkspaceDto.getUserId();
        Long cId = createWorkspaceDto.getCId();
        List<Long> skillIds = createWorkspaceDto.getSkillIds();
        List<SubagentDto> subagents = createWorkspaceDto.getSubagents();

        if (userId == null || cId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentRequiredParamEmpty);
        }

        List<SkillConfigDto> toPushSkills = new ArrayList<>();
        List<String> skillUrls = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(skillIds)) {
            List<SkillConfig> skillConfigs = skillDomainService.listByIds(skillIds);
            if (CollectionUtils.isNotEmpty(skillConfigs)) {
                for (SkillConfig skillConfig : skillConfigs) {
                    Published.PublishStatus publishStatus = skillConfig.getPublishStatus();
                    if (publishStatus != Published.PublishStatus.Published) {
                        log.warn("[createWorkspace] userId={} cId={} skillId={} 技能未发布，跳过", userId, cId, skillConfig.getId());
                        continue;
                    }

                    PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Skill, skillConfig.getId(), true);
                    if (publishedDto == null) {
                        log.warn("[createWorkspace] userId={} cId={} skillId={} 技能无发布信息，跳过", userId, cId, skillConfig.getId());
                        continue;
                    }
                    String config = publishedDto.getConfig();
                    SkillConfigDto dto = parseSkillConfig(config);
                    SkillNameUtil.backfillName(dto, iFileAccessService);

                    if (isV2Config(dto)) {
                        if (StringUtils.isNotBlank(dto.getZipFileUrl())) {
                            skillUrls.add(iFileAccessService.getFileUrlWithAk(dto.getZipFileUrl(), true));
                        }
                        continue;
                    }

                    if (CollectionUtils.isEmpty(dto.getFiles())) {
                        log.warn("[createWorkspace] userId={} cId={} skillId={} 技能无文件，跳过", userId, cId, skillConfig.getId());
                        continue;
                    }

                    log.info("[createWorkspace] userId={} cId={} skillId={}, skillName={} 技能打包开始", userId, cId, dto.getId(), dto.getName());
                    toPushSkills.add(dto);
                }
            }
        }

        MultipartFile zipFile = null;
        if (CollectionUtils.isNotEmpty(toPushSkills) || CollectionUtils.isNotEmpty(subagents)) {
            zipFile = buildZip(toPushSkills, subagents);
        }

        String mcpServersConfig = createWorkspaceDto.getMcpServersConfig() != null ? JSON.toJSONString(createWorkspaceDto.getMcpServersConfig()) : null;
        String permissionsConfig = createWorkspaceDto.getPermissionsConfig() != null ? JSON.toJSONString(createWorkspaceDto.getPermissionsConfig()) : null;
        String hooksConfig = createWorkspaceDto.getHooksConfig() != null ? JSON.toJSONString(createWorkspaceDto.getHooksConfig()) : null;
        List<HookScriptDto> hookScripts = createWorkspaceDto.getHookScripts();
        String hookScriptsJson = null;
        if (CollectionUtils.isNotEmpty(hookScripts)) {
            hookScriptsJson = JSON.toJSONString(hookScripts);
        }

        Map<String, Object> result = null;
        try {
            result = workspaceRpcClient.createWorkSpaceV2(userId, cId, zipFile, skillUrls, mcpServersConfig, permissionsConfig, hooksConfig, hookScriptsJson);
        } catch (Exception e) {
            log.warn("[createWorkspace] userId={} cId={} 调用 createWorkSpaceV2 异常，准备回退到 createWorkSpace", userId, cId, e);
        }

        boolean v2Success = isSuccess(result);
        if (!v2Success) {
            String message = result == null ? "response is null" : String.valueOf(result.getOrDefault("message", "createWorkSpaceV2 failed"));
            log.warn("[createWorkspace] userId={} cId={} createWorkSpaceV2失败，准备回退。message={}", userId, cId, message);

            MultipartFile fallbackZipFile = buildZipWithSkillUrls(zipFile, skillUrls, false);
            result = workspaceRpcClient.createWorkSpace(userId, cId, fallbackZipFile);
        }

        if (result == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkspaceCreateFailed);
        }

        Object successObj = result.get("success");
        if (successObj instanceof Boolean && !(Boolean) successObj) {
            String message = result.getOrDefault("message", "创建工作空间失败").toString();
            log.error("[createWorkspace] userId={} cId={} 创建工作空间失败, message={}", userId, cId, message);
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail, message);
        }
        log.info("[createWorkspace] userId={} cId={} 创建工作空间成功", userId, cId);
    }

    @Override
    public void addSkillsToWorkspace(AddSkillsToWorkspaceDto addSkillsToWorkspaceDto) {
        Long userId = addSkillsToWorkspaceDto.getUserId();
        Long cId = addSkillsToWorkspaceDto.getCId();
        List<SkillConfigDto> oldSkills = new ArrayList<>();
        List<String> skillUrls = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(addSkillsToWorkspaceDto.getSkillConfigs())) {
            for (SkillConfigDto skillConfig : addSkillsToWorkspaceDto.getSkillConfigs()) {
                if (isV2Config(skillConfig)) {
                    if (StringUtils.isNotBlank(skillConfig.getZipFileUrl())) {
                        skillUrls.add(iFileAccessService.getFileUrlWithAk(skillConfig.getZipFileUrl(), true));
                    }
                } else {
                    SkillNameUtil.backfillName(skillConfig, iFileAccessService);
                    oldSkills.add(skillConfig);
                }
            }
        }
        appendDynamicAddLockFiles(oldSkills);
        MultipartFile baseZipFile = buildZip(oldSkills, Collections.emptyList());

        Map<String, Object> result = null;
        try {
            result = workspaceRpcClient.pushSkillsToWorkspaceV2(userId, cId, baseZipFile, skillUrls);
        } catch (Exception e) {
            log.warn("[addSkills] userId={} cId={} 调用 pushSkillsToWorkspaceV2 异常，准备回退到 pushSkillsToWorkspace", userId, cId, e);
        }
        // 回退老版本接口
        if (!isSuccess(result)) {
            String message = result == null ? "response is null" : String.valueOf(result.getOrDefault("message", "pushSkillsToWorkspaceV2 failed"));
            log.warn("[addSkills] userId={} cId={} pushSkillsToWorkspaceV2失败，准备回退。message={}", userId, cId, message);

            MultipartFile zipFile = buildZipWithSkillUrls(baseZipFile, skillUrls, true);
            if (zipFile == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillZipPackFailed);
            }
            result = workspaceRpcClient.pushSkillsToWorkspace(userId, cId, zipFile);
        }

        if (result == null) {
            log.error("[addSkills] userId={} cId={} 推送技能文件失败，响应为空", userId, cId);
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillFilePushFailed);
        }
        Object successObj = result.get("success");
        if (successObj instanceof Boolean && !(Boolean) successObj) {
            String message = result.getOrDefault("message", "推送技能文件失败").toString();
            log.error("[addSkills] userId={} cId={} 推送技能文件失败, message={}", userId, cId, message);
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillFilePushFailed, message);
        }

        log.info("[addSkills] userId={} cId={} 动态增加技能完成", userId, cId);
    }

    @Override
    public void initProjectTemplate(InitProjectTemplateDto dto) {
        Long userId = dto.getUserId();
        Long cId = dto.getCId();
        InitProjectTemplateDto.ProjectType projectType = dto.getProjectType();
        InitProjectTemplateDto.ProgrammingLanguage programmingLanguage = dto.getProgrammingLanguage();

        log.info("[initProjectTemplate] userId={} cId={} projectType={} programmingLanguage={} 开始初始化项目模板", userId, cId, projectType, programmingLanguage);

        if (userId == null || cId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "userId/cId");
        }
        if (projectType == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "projectType");
        }
        if (projectType == InitProjectTemplateDto.ProjectType.AGENT && programmingLanguage == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "programmingLanguage");
        }

        MultipartFile templateFile = null;

        if (projectType == InitProjectTemplateDto.ProjectType.AGENT) {
            String templateFileName;
            switch (programmingLanguage) {
                case PYTHON:
                    templateFileName = "deepagent-app-py.zip";
                    break;
                default:
                    templateFileName = "deepagents-flow-ts.zip";
                    break;
            }

            // 从 classpath 读取模板 zip 文件
            String classpathLocation = "templates/" + templateFileName;
            byte[] zipBytes;
            try {
                ClassPathResource resource = new ClassPathResource(classpathLocation);
                try (InputStream is = resource.getInputStream()) {
                    zipBytes = is.readAllBytes();
                }
            } catch (IOException e) {
                log.error("[initProjectTemplate] userId={} cId={} 读取模板文件失败, classpathLocation={}", userId, cId, classpathLocation, e);
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                        "Template file not found: " + templateFileName);
            }
            templateFile = new InMemoryMultipartFile("file", templateFileName, "application/zip", zipBytes);
        } else if (projectType == InitProjectTemplateDto.ProjectType.SKILL) {
            templateFile = buildSkillTemplateZip();
        }

        boolean enableGit = resolveEnableGit(cId);
        log.info("[initProjectTemplate] userId={} cId={} enableGit={}", userId, cId, enableGit);

        // 调用 file-server 接口
        Map<String, Object> result = workspaceRpcClient.initProjectTemplate(userId, cId, templateFile, enableGit);

        // 结果校验
        if (result == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentTemplateInitFailed, "result is null");
        }
        Object successObj = result.get("success");
        if (successObj instanceof Boolean && !(Boolean) successObj) {
            String message = result.getOrDefault("message", "初始化项目模板失败").toString();
            log.error("[initProjectTemplate] userId={} cId={} 初始化项目模板失败, message={}", userId, cId, message);
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.agentTemplateInitFailed, message);
        }

        log.info("[initProjectTemplate] userId={} cId={} 初始化项目模板成功", userId, cId);
    }

    private boolean resolveEnableGit(Long cId) {
        Conversation conversation = conversationDomainService.getConversation(cId);
        if (conversation == null || conversation.getAgentId() == null) {
            return false;
        }
        AgentConfigDto executingAgent;
        if (YesOrNoEnum.Y.getKey().equals(conversation.getDevMode())) {
            executingAgent = agentApplicationService.queryConfigForTestExecute(conversation.getAgentId());
        } else {
            executingAgent = agentApplicationService.queryPublishedConfigForExecute(conversation.getAgentId());
        }
        if (executingAgent == null) {
            return false;
        }
        return YesOrNoEnum.Y.getKey().equals(executingAgent.getEnableVersionControl());
    }

    @Override
    public void installProject(InstallProjectDto dto) {
        Long userId = dto.getUserId();
        Long cId = dto.getCId();
        InitProjectTemplateDto.ProgrammingLanguage programmingLanguage = dto.getProgrammingLanguage();

        log.info("[installProject] userId={} cId={} programmingLanguage={} 开始安装项目依赖", userId, cId, programmingLanguage);

        if (userId == null || cId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "userId/cId");
        }
        if (programmingLanguage == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "programmingLanguage");
        }

        Map<String, Object> result = workspaceRpcClient.installProject(userId, cId, programmingLanguage.getValue());
        if (result == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.workspaceExecuteCommandFailed, "installProject result is null");
        }
        Object successObj = result.get("success");
        if (successObj instanceof Boolean && !(Boolean) successObj) {
            String message = String.valueOf(result.getOrDefault("message", "安装项目依赖失败"));
            log.error("[installProject] userId={} cId={} 安装项目依赖失败, message={}", userId, cId, message);
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.workspaceExecuteCommandFailed, message);
        }

        log.info("[installProject] userId={} cId={} 安装项目依赖成功", userId, cId);
    }

    @Override
    public List<AgentPublishVersionDto> packageAgent(PackageAgentDto dto) {
        Long userId = dto.getUserId();
        Long cId = dto.getCId();
        Long agentId = dto.getAgentId();

        log.info("[packageAgent] userId={} cId={} agentId={} programmingLanguage={} 开始打包Agent", userId, cId, agentId, dto.getProgrammingLanguage());

        if (userId == null || cId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "userId/cId");
        }
        if (agentId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "agentId");
        }

        // 1. 先 git add + commit 确保工作区改动已提交（仅版本控制开启时）
        boolean enableGit = resolveEnableGit(cId);
        if (enableGit) {
            gitCommitAndPush(userId, cId);
        }

        // 2. 获取 git commit hash
        String gitCommit = enableGit ? getGitCommitHash(userId, cId) : null;
        log.info("[packageAgent] userId={} cId={} agentId={} gitCommit={}", userId, cId, agentId, gitCommit);

        // 3. 查询 agent_config 中的已有版本记录，检查是否需要重新打包
        AgentConfig agentConfig = agentDomainService.queryById(agentId);
        List<AgentPublishVersionDto> existingVersions = agentConfig != null ? agentConfig.getPublishVersion() : null;
        if (CollectionUtils.isNotEmpty(existingVersions)) {
            AgentPublishVersionDto latestVersion = existingVersions.stream()
                    .filter(v -> Boolean.TRUE.equals(v.getLatest()))
                    .findFirst()
                    .orElse(null);
            if (latestVersion != null && gitCommit != null && gitCommit.equals(latestVersion.getGitCommit())) {
                // git commit 没有变化，不需要重新打包
                log.info("[packageAgent] userId={} cId={} agentId={} git commit未变化, 跳过打包, version={}", userId, cId, agentId, latestVersion.getVersion());
                return existingVersions;
            }
        }

        // 4. 生成递增版本号
        String version = getNextVersion(existingVersions);
        log.info("[packageAgent] userId={} cId={} agentId={} newVersion={}", userId, cId, agentId, version);

        // 5. 调用 file-server 构建 agent 产物
        Map<String, Object> buildResult = workspaceRpcClient.buildAgentPackage(userId, cId, agentId, version);
        if (buildResult == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.workspaceExecuteCommandFailed, "buildAgentPackage result is null");
        }
        Object buildSuccessObj = buildResult.get("success");
        if (buildSuccessObj instanceof Boolean && !(Boolean) buildSuccessObj) {
            String message = String.valueOf(buildResult.getOrDefault("message", "buildAgentPackage failed"));
            log.error("[packageAgent] userId={} cId={} 构建产物失败, message={}", userId, cId, message);
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.workspaceExecuteCommandFailed, message);
        }

        // 6. 遍历产物列表，下载并上传
        Object artifactsObj = buildResult.get("artifacts");
        List<AgentPublishVersionDto.PackageArtifact> packages = new ArrayList<>();

        if (artifactsObj instanceof List) {
            for (Object artifactObj : (List<?>) artifactsObj) {
                if (!(artifactObj instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> artifact = (Map<String, Object>) artifactObj;

                String artifactPath = String.valueOf(artifact.getOrDefault("path", ""));
                String fileName = String.valueOf(artifact.getOrDefault("fileName", ""));
                String platform = String.valueOf(artifact.getOrDefault("platform", ""));

                if (StringUtils.isBlank(artifactPath) || StringUtils.isBlank(fileName)) {
                    continue;
                }
                if (StringUtils.isBlank(platform)) {
                    log.warn("[packageAgent] userId={} cId={} 无法从文件名提取platform, fileName={}", userId, cId, fileName);
                    continue;
                }

                try {
                    String staticPrefix = "computer/static/" + userId + "/" + cId;
                    byte[] fileBytes = computerFileClient.getStaticFileAsBytes(cId, staticPrefix, artifactPath, "packageAgent:" + userId + ":" + cId);

                    String lowerFileName = fileName.toLowerCase();
                    String contentType = lowerFileName.endsWith(".zip") ? "application/zip" : "application/gzip";
                    InMemoryMultipartFile multipartFile = new InMemoryMultipartFile("file", fileName, contentType, fileBytes);
                    Long tenantId = RequestContext.get() != null ? RequestContext.get().getTenantId() : null;
                    Long uploadUserId = RequestContext.get() != null ? RequestContext.get().getUserId() : null;
                    FileRecordDomain fileRecord = fileManagementService.uploadFile(multipartFile, tenantId, uploadUserId, "agent_package", agentId, artifactPath, true);

                    String fileUrl = fileRecord.getFileUrl();
                    packages.add(AgentPublishVersionDto.PackageArtifact.builder()
                            .platform(platform)
                            .url(fileUrl)
                            .build());

                    log.info("[packageAgent] userId={} cId={} 上传产物成功, fileName={}, platform={}, fileUrl={}", userId, cId, fileName, platform, fileUrl);
                } catch (Exception e) {
                    log.error("[packageAgent] userId={} cId={} 处理产物失败, artifactPath={}", userId, cId, artifactPath, e);
                    throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.agentPackageFailed, "Failed to process artifact: " + artifactPath);
                }
            }
        }

        if (packages.isEmpty()) {
            log.error("[packageAgent] userId={} cId={} 打包产物处理失败，无有效产物", userId, cId);
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.agentPackageFailed, "No valid package artifacts found");
        }

        // 7. 清理沙箱打包产物
        try {
            workspaceRpcClient.cleanupBuildArtifacts(userId, cId);
        } catch (Exception e) {
            log.warn("[packageAgent] userId={} cId={} 清理打包产物目录失败, 不影响发布流程", userId, cId, e);
        }

        // 8. 保存版本记录到 agent_config.publishVersion
        List<AgentPublishVersionDto> updatedVersions = saveVersionToAgentConfig(agentId, existingVersions, version, gitCommit, packages);

        log.info("[packageAgent] userId={} cId={} agentId={} 打包完成, version={}, gitCommit={}, packages={}", userId, cId, agentId, version, gitCommit, packages.size());
        return updatedVersions;
    }

    @Override
    public List<SkillFileDto> snapshotSkillFilesFromSandbox(Long userId, Long cId, String targetType, Long skillId) {
        if (userId == null || cId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "userId/cId");
        }

        // 1. 获取技能
        byte[] zipBytes = zipAndDownloadFromSandbox(userId, cId);

        // 2. 解析 zip，逐个文件上传到文件服务
        List<SkillFileDto> results = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (entry.isDirectory()) {
                    SkillFileDto dirDto = new SkillFileDto();
                    dirDto.setName(entryName);
                    dirDto.setIsDir(true);
                    results.add(dirDto);
                    continue;
                }
                // 跳过隐藏文件和无关文件
                if (entryName.startsWith(".") || entryName.contains("/__pycache__/") || entryName.contains("/node_modules/")) {
                    continue;
                }

                byte[] fileBytes = zis.readAllBytes();
                String fileName = entryName;
                int lastSlash = entryName.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash < entryName.length() - 1) {
                    fileName = entryName.substring(lastSlash + 1);
                }
                String contentType = FileTypeUtils.getContentTypeByFileName(fileName).toString();
                InMemoryMultipartFile multipartFile = new InMemoryMultipartFile("file", fileName, contentType, fileBytes);
                Long tenantId = RequestContext.get() != null ? RequestContext.get().getTenantId() : null;
                Long uploadUserId = RequestContext.get() != null ? RequestContext.get().getUserId() : null;
                FileRecordDomain fileRecord = fileManagementService.uploadFile(multipartFile, tenantId, uploadUserId, targetType, skillId, entryName, true);

                SkillFileDto fileDto = new SkillFileDto();
                fileDto.setName(entryName);
                fileDto.setFileProxyUrl(fileRecord.getFileUrl());
                results.add(fileDto);
            }
        } catch (IOException e) {
            log.error("[snapshotSkillFilesFromSandbox] 解析zip失败, userId={}, cId={}", userId, cId, e);
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.agentSkillZipPackFailed,
                    "Failed to parse skill zip");
        }

        log.info("[snapshotSkillFilesFromSandbox] userId={} cId={} skillId={} 从沙箱取回技能文件完成, fileCount={}", userId, cId, skillId, results.size());
        return results;
    }

    @Override
    public void deleteWorkspace(Long userId, Long cId) {
        if (userId == null || cId == null) {
            return;
        }
        try {
            Map<String, Object> result = workspaceRpcClient.deleteWorkspace(userId, cId);
            if (result != null) {
                Object successObj = result.get("success");
                if (successObj instanceof Boolean && !(Boolean) successObj) {
                    log.warn("[deleteWorkspace] userId={} cId={} 删除工作空间失败, result={}", userId, cId, result);
                } else {
                    log.info("[deleteWorkspace] userId={} cId={} 删除工作空间成功", userId, cId);
                }
            }
        } catch (Exception e) {
            log.warn("[deleteWorkspace] userId={} cId={} 删除工作空间异常", userId, cId, e);
        }
    }

    @Override
    public SkillExportResultDto exportSkillFromSandbox(Long userId, Long cId, String skillName) {
        if (userId == null || cId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "userId/cId");
        }

        // 1. 获取技能
        byte[] zipBytes = zipAndDownloadFromSandbox(userId, cId);

        // 2. 构建返回结果
        String exportFileName = (StringUtils.isNotBlank(skillName) ? skillName : "skill") + ".zip";
        SkillExportResultDto resultDto = new SkillExportResultDto();
        resultDto.setFileName(exportFileName);
        resultDto.setData(zipBytes);
        resultDto.setContentType("application/zip");

        log.info("[exportSkillFromSandbox] userId={} cId={} skillName={} 导出完成, zipSize={}", userId, cId, skillName, zipBytes.length);
        return resultDto;
    }

    @Override
    public void copySandboxWorkspace(Long srcUserId, Long srcCId, Long destUserId, Long destCId) {
        if (srcUserId == null || srcCId == null || destUserId == null || destCId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "srcUserId/srcCId/destUserId/destCId");
        }

        log.info("[copySandboxWorkspace] srcUserId={} srcCId={} destUserId={} destCId={} 开始复制工作空间", srcUserId, srcCId, destUserId, destCId);

        // 1. 打包下载
        byte[] zipBytes = zipAndDownloadFromSandbox(srcUserId, srcCId);

        // 2. 使用 initProjectTemplate 将 zip 推送到新工作空间，按版本开关决定是否初始化 git
        InMemoryMultipartFile zipFile = new InMemoryMultipartFile("file", "skill-copy.zip", "application/zip", zipBytes);
        boolean enableGit = resolveEnableGit(destCId);
        Map<String, Object> initResult = workspaceRpcClient.initProjectTemplate(destUserId, destCId, zipFile, enableGit);

        if (initResult == null) {
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.agentTemplateInitFailed, "initProjectTemplate result is null");
        }
        Object successObj = initResult.get("success");
        if (successObj instanceof Boolean && !(Boolean) successObj) {
            String message = String.valueOf(initResult.getOrDefault("message", "initProjectTemplate failed"));
            log.error("[copySandboxWorkspace] destUserId={} destCId={} initProjectTemplate失败, message={}", destUserId, destCId, message);
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.agentTemplateInitFailed, message);
        }

        log.info("[copySandboxWorkspace] srcUserId={} srcCId={} destUserId={} destCId={} 复制工作空间完成", srcUserId, srcCId, destUserId, destCId);
    }

    @Override
    public void uploadZipToWorkspace(Long userId, Long cId, String zipFileUrl) {
        if (userId == null || cId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "userId/cId");
        }
        if (StringUtils.isBlank(zipFileUrl)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "zipFileUrl");
        }

        log.info("[uploadZipToWorkspace] userId={} cId={} 开始上传 zip 到工作空间", userId, cId);

        // 1. 下载 zip 字节（UrlFile 内部会生成签名下载地址，并兼容 S3 presigned URL）
        byte[] zipBytes;
        try {
            zipBytes = UrlFile.urlToBytes(zipFileUrl);
        } catch (Exception e) {
            log.error("[uploadZipToWorkspace] userId={} cId={} 下载 zip 失败, zipFileUrl={}", userId, cId, zipFileUrl, e);
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.agentTemplateInitFailed, "Failed to download zip: " + e.getMessage());
        }
        if (zipBytes == null || zipBytes.length == 0) {
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.agentTemplateInitFailed, "Downloaded zip is empty");
        }

        uploadZipBytesToWorkspace(userId, cId, zipBytes);
    }

    @Override
    public void uploadZipBytesToWorkspace(Long userId, Long cId, byte[] zipBytes) {
        if (userId == null || cId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "userId/cId");
        }
        if (zipBytes == null || zipBytes.length == 0) {
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.agentTemplateInitFailed, "Zip is empty");
        }

        log.info("[uploadZipBytesToWorkspace] userId={} cId={} 开始上传 zip 到工作空间", userId, cId);

        zipBytes = stripTopLevelDirIfNeeded(zipBytes);

        InMemoryMultipartFile zipFile = new InMemoryMultipartFile("file", "workspace.zip", "application/zip", zipBytes);
        boolean enableGit = resolveEnableGit(cId);
        Map<String, Object> initResult = workspaceRpcClient.initProjectTemplate(userId, cId, zipFile, enableGit);

        if (initResult == null) {
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.agentTemplateInitFailed, "initProjectTemplate result is null");
        }
        Object successObj = initResult.get("success");
        if (successObj instanceof Boolean && !(Boolean) successObj) {
            String message = String.valueOf(initResult.getOrDefault("message", "initProjectTemplate failed"));
            log.error("[uploadZipBytesToWorkspace] userId={} cId={} initProjectTemplate失败, message={}", userId, cId, message);
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.agentTemplateInitFailed, message);
        }

        log.info("[uploadZipBytesToWorkspace] userId={} cId={} 上传 zip 到工作空间完成", userId, cId);
    }

    //
    // ------------------ 以下是private方法 -----------------------
    //

    /**
     * 调用 file-server 的 zip-workspace 接口打包工作空间文件并下载 zip 字节
     *
     * @param userId 用户ID
     * @param cId    会话ID
     * @return zip 文件的字节数组
     */
    private byte[] zipAndDownloadFromSandbox(Long userId, Long cId) {
        byte[] zipBytes = workspaceRpcClient.zipWorkspace(userId, cId, null);
        if (zipBytes == null || zipBytes.length == 0) {
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.workspaceExecuteCommandFailed,
                    "Failed to zip files in sandbox: empty response");
        }
        log.info("userId={} cId={} 沙箱打包完成, zipSize={}", userId, cId, zipBytes.length);
        return zipBytes;
    }

    /**
     * 如果 zip 只有一个顶层目录，去掉该顶层目录前缀后重新打包；否则原样返回。
     * 例如 entry 为 "my-skill/SKILL.md" 时会去掉 "my-skill/" 前缀。
     */
    private byte[] stripTopLevelDirIfNeeded(byte[] zipBytes) {
        // 第一次扫描：判断是否所有 entry 都在同一个顶层目录下
        String topLevelDir = null;
        boolean singleTopLevel = true;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = normalizeZipPath(entry.getName());
                int slash = name.indexOf('/');
                if (slash < 0) {
                    // 根目录下存在文件，不存在单一顶层目录
                    singleTopLevel = false;
                    break;
                }
                String top = name.substring(0, slash);
                if (topLevelDir == null) {
                    topLevelDir = top;
                } else if (!topLevelDir.equals(top)) {
                    singleTopLevel = false;
                    break;
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            log.warn("[stripTopLevelDir] 解析zip判断顶层目录失败, 原样返回", e);
            return zipBytes;
        }

        if (!singleTopLevel || topLevelDir == null) {
            return zipBytes;
        }

        final String prefix = topLevelDir + "/";
        // 第二次扫描：去掉顶层目录前缀后重新打包
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = normalizeZipPath(entry.getName());
                    if (name.startsWith(prefix)) {
                        name = name.substring(prefix.length());
                    }
                    if (StringUtils.isBlank(name)) {
                        // 去掉前缀后为空（即顶层目录 entry 本身），跳过
                        zis.closeEntry();
                        continue;
                    }
                    ZipEntry newEntry = new ZipEntry(name);
                    zos.putNextEntry(newEntry);
                    if (!entry.isDirectory()) {
                        zos.write(zis.readAllBytes());
                    }
                    zos.closeEntry();
                    zis.closeEntry();
                }
            }
            zos.finish();
            log.info("[stripTopLevelDir] 去掉顶层目录 '{}' 后重新打包完成", topLevelDir);
            return baos.toByteArray();
        } catch (IOException e) {
            log.warn("[stripTopLevelDir] 重新打包失败, 原样返回", e);
            return zipBytes;
        }
    }

    /**
     * 执行 git add + commit，确保所有改动已提交
     */
    private void gitCommitAndPush(Long userId, Long cId) {
        try {
            gitRpcClient.commitTaskAgent(cId, userId, "auto commit before package", null, null, null);
        } catch (Exception e) {
            log.warn("[packageAgent] git commit失败, userId={}, cId={}", userId, cId, e);
        }
    }

    /**
     * 获取沙箱工作空间中的 git commit hash（HEAD）
     */
    private String getGitCommitHash(Long userId, Long cId) {
        try {
            Map<String, Object> result = gitRpcClient.logTaskAgent(cId, userId, 1, 1, null, null);
            if (result != null) {
                Object successObj = result.get("success");
                if (successObj instanceof Boolean && (Boolean) successObj) {
                    Object commitsObj = result.get("commits");
                    if (commitsObj instanceof List && !((List<?>) commitsObj).isEmpty()) {
                        Object firstCommit = ((List<?>) commitsObj).get(0);
                        if (firstCommit instanceof Map) {
                            String hash = String.valueOf(((Map<?, ?>) firstCommit).get("hash"));
                            if (hash.length() >= 7) {
                                return hash;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[packageAgent] 获取git commit hash失败, userId={}, cId={}", userId, cId, e);
        }
        return null;
    }

    /**
     * 根据 agent_config 中的已有版本记录获取下一个版本号
     */
    private String getNextVersion(List<AgentPublishVersionDto> existingVersions) {
        if (CollectionUtils.isNotEmpty(existingVersions)) {
            String latest = existingVersions.stream()
                    .filter(v -> Boolean.TRUE.equals(v.getLatest()))
                    .findFirst()
                    .map(AgentPublishVersionDto::getVersion)
                    .orElse(null);
            if (latest != null) {
                return incrementVersion(latest);
            }
            // 没有 latest 标记，取最后一个版本
            String last = existingVersions.get(existingVersions.size() - 1).getVersion();
            if (last != null) {
                return incrementVersion(last);
            }
        }
        return "1.0.0";
    }

    /**
     * 保存版本记录到 agent_config：将所有旧版本 latest=false，追加新版本 latest=true
     * @return 最新的完整版本列表
     */
    private List<AgentPublishVersionDto> saveVersionToAgentConfig(Long agentId, List<AgentPublishVersionDto> existingVersions,
                                           String version, String gitCommit, List<AgentPublishVersionDto.PackageArtifact> packages) {
        try {
            List<AgentPublishVersionDto> updatedVersions = new ArrayList<>();
            if (CollectionUtils.isNotEmpty(existingVersions)) {
                // 所有旧版本标记为 latest=false
                existingVersions.forEach(v -> v.setLatest(false));
                updatedVersions.addAll(existingVersions);
            }
            // 追加新版本
            updatedVersions.add(AgentPublishVersionDto.builder()
                    .version(version)
                    .gitCommit(gitCommit)
                    .latest(true)
                    .packages(packages)
                    .build());

            AgentConfig updateConfig = new AgentConfig();
            updateConfig.setId(agentId);
            updateConfig.setPublishVersion(updatedVersions);
            agentDomainService.update(updateConfig);
            return updatedVersions;
        } catch (Exception e) {
            log.error("[packageAgent] 保存版本记录到agent_config失败, agentId={}, version={}", agentId, version, e);
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.agentPackageFailed, "Failed to save version to agent config");
        }
    }

    /**
     * 十进制递增版本号：x.y.z 每位 0-9，满十进一
     * 1.0.9 → 1.1.0, 1.9.9 → 2.0.0
     */
    private String incrementVersion(String version) {
        try {
            String[] parts = version.split("\\.");
            if (parts.length == 3) {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                int patch = Integer.parseInt(parts[2]) + 1;
                if (patch >= 10) {
                    patch = 0;
                    minor++;
                }
                if (minor >= 10) {
                    minor = 0;
                    major++;
                }
                return major + "." + minor + "." + patch;
            }
        } catch (NumberFormatException ignored) {
        }
        return "1.0.0";
    }

    /**
     * 构建动态增加技能的锁标志文件
     * 写入技能文件夹根目录下，用于标识该技能为动态增加
     */
    private SkillFileDto buildDynamicAddLockFile() {
        SkillFileDto lockFile = new SkillFileDto();
        lockFile.setName(".dynamic_add.lock");
        lockFile.setContents("dynamic_add\n");
        lockFile.setOperation("create");
        lockFile.setIsDir(false);
        return lockFile;
    }

    private void appendDynamicAddLockFiles(List<SkillConfigDto> skills) {
        if (CollectionUtils.isEmpty(skills)) {
            return;
        }
        for (SkillConfigDto skill : skills) {
            if (skill == null || CollectionUtils.isEmpty(skill.getFiles())) {
                continue;
            }
            boolean hasLockFile = false;
            for (SkillFileDto file : skill.getFiles()) {
                if (file != null && ".dynamic_add.lock".equals(file.getName())) {
                    hasLockFile = true;
                    break;
                }
            }
            if (!hasLockFile) {
                skill.getFiles().add(buildDynamicAddLockFile());
            }
        }
    }

    /**
     * 构建技能模板 zip：从 skill-template.json 读取模板文件列表，打包成 zip
     */
    private MultipartFile buildSkillTemplateZip() {
        SkillConfigDto skillConfigDto = skillApplicationService.getSkillTemplate();
        List<SkillFileDto> files = skillConfigDto.getFiles();
        if (CollectionUtils.isEmpty(files)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillTemplateReadFailed,
                    "skill template has no files");
        }
        return buildSkillFilesZip(files, "skill-template.zip");
    }

    /**
     * 将 SkillFileDto 列表打包成 zip 文件
     */
    private MultipartFile buildSkillFilesZip(List<SkillFileDto> files, String zipFileName) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            for (SkillFileDto file : files) {
                if (file == null || StringUtils.isBlank(file.getName())) {
                    continue;
                }
                if (Boolean.TRUE.equals(file.getIsDir())) {
                    String dirPath = file.getName().endsWith("/") ? file.getName() : file.getName() + "/";
                    zos.putNextEntry(new ZipEntry(dirPath));
                    zos.closeEntry();
                } else {
                    zos.putNextEntry(new ZipEntry(file.getName()));
                    if (file.getContents() != null) {
                        zos.write(file.getContents().getBytes(StandardCharsets.UTF_8));
                    }
                    zos.closeEntry();
                }
            }
            zos.finish();
            return new InMemoryMultipartFile("file", zipFileName, "application/zip", baos.toByteArray());
        } catch (IOException e) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillZipPackFailed, e.getMessage());
        }
    }

    /**
     * 将skills和subagents打包成 zip
     */
    private MultipartFile buildZip(List<SkillConfigDto> skills, List<SubagentDto> subagents) {
        if (CollectionUtils.isEmpty(skills) && CollectionUtils.isEmpty(subagents)) {
            return null;
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(); ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {

            // 显式创建 skills 根目录，避免下游只按目录 entry 检查而认为没有 skills 目录
            ZipEntry skillsRoot = new ZipEntry("skills/");
            zos.putNextEntry(skillsRoot);
            zos.closeEntry();

            // 显式创建 agents 根目录
            ZipEntry agentsRoot = new ZipEntry("agents/");
            zos.putNextEntry(agentsRoot);
            zos.closeEntry();

            // 用于跟踪已添加的条目，避免重复添加
            Set<String> addedEntries = new HashSet<>();
            addedEntries.add("skills/");
            addedEntries.add("agents/");

            // 处理 skills
            if (CollectionUtils.isNotEmpty(skills)) {
                for (SkillConfigDto skill : skills) {
                    if (skill == null || CollectionUtils.isEmpty(skill.getFiles())) {
                        continue;
                    }

                    String skillName = StringUtils.isNotBlank(skill.getEnName()) ? skill.getEnName() : skill.getName();
                    if (StringUtils.isBlank(skillName)) {
                        continue;
                    }

                    String skillDir = "skills/" + skillName + "/";

                    // 确保技能目录被创建
                    if (!addedEntries.contains(skillDir)) {
                        ZipEntry skillDirEntry = new ZipEntry(skillDir);
                        zos.putNextEntry(skillDirEntry);
                        zos.closeEntry();
                        addedEntries.add(skillDir);
                    }

                    for (SkillFileDto fileDto : skill.getFiles()) {
                        if (fileDto == null || fileDto.getName() == null || fileDto.getName().isBlank()) {
                            continue;
                        }

                        String fileName = fileDto.getName();
                        // 规范化文件名：去除前导斜杠，避免双斜杠
                        if (fileName.startsWith("/")) {
                            fileName = fileName.substring(1);
                        }

                        String entryName = skillDir + fileName;

                        // 如果是目录，确保路径以/结尾
                        if (Boolean.TRUE.equals(fileDto.getIsDir())) {
                            if (!entryName.endsWith("/")) {
                                entryName = entryName + "/";
                            }
                            // 跳过已添加的目录
                            if (addedEntries.contains(entryName)) {
                                continue;
                            }
                            addedEntries.add(entryName);
                            ZipEntry entry = new ZipEntry(entryName);
                            zos.putNextEntry(entry);
                            zos.closeEntry();
                        } else {
                            // 对于文件，先确保所有父目录都被创建
                            ensureParentDirectories(zos, entryName, skillDir, addedEntries);

                            // 跳过已添加的文件
                            if (addedEntries.contains(entryName)) {
                                continue;
                            }
                            addedEntries.add(entryName);
                            ZipEntry entry = new ZipEntry(entryName);
                            zos.putNextEntry(entry);

                            String contents = fileDto.getContents();
                            if (contents != null) {
                                byte[] bytes = getFileBytes(contents, fileDto.getName());
                                zos.write(bytes);
                            }

                            zos.closeEntry();
                        }
                    }
                }
            }

            // 处理 subagents，每个 SubagentDto 转换成一个 markdown 文件
            if (!CollectionUtils.isEmpty(subagents)) {
                for (SubagentDto subagent : subagents) {
                    if (subagent == null || StringUtils.isBlank(subagent.getContent())) {
                        continue;
                    }

                    String fileName = StringUtils.isNotBlank(subagent.getName())
                            ? subagent.getName()
                            : MarkdownExtractUtil.extractFieldValue(subagent.getContent(), "name");
                    if (StringUtils.isBlank(fileName)) {
                        continue;
                    }
                    // 确保文件名以 .md 结尾
                    if (!fileName.toLowerCase().endsWith(".md")) {
                        fileName = fileName + ".md";
                    }
                    String entryName = "agents/" + fileName;

                    // 跳过已添加的文件
                    if (addedEntries.contains(entryName)) {
                        continue;
                    }
                    addedEntries.add(entryName);

                    ZipEntry entry = new ZipEntry(entryName);
                    zos.putNextEntry(entry);

                    String content = subagent.getContent();
                    if (content != null) {
                        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                        zos.write(bytes);
                    }

                    zos.closeEntry();
                }
            }

            zos.finish();
            byte[] zipBytes = baos.toByteArray();

            if (zipBytes.length == 0) {
                return null;
            }

            return new InMemoryMultipartFile("file", "skills.zip", "application/zip", zipBytes);
        } catch (IOException e) {
            log.error("[skill/agent] 打包skill/agent zip失败", e);
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillAgentPackFailed);
        }
    }

    /**
     * 获取文件的字节数组
     * 如果是二进制文件且内容为 base64 编码，则解码；否则直接转换为字节数组
     */
    private byte[] getFileBytes(String contents, String fileName) {
        if (contents == null || contents.isBlank()) {
            return new byte[0];
        }

        // 如果是文本文件，直接转换为字节数组
        if (FileTypeUtils.isTextFile(fileName)) {
            return contents.getBytes(StandardCharsets.UTF_8);
        }

        // 非文本文件（二进制），尝试解码 base64
        try {
            // 尝试解码 base64
            return Base64.getDecoder().decode(contents);
        } catch (IllegalArgumentException e) {
            // 如果不是有效的 base64，则按文本处理（兼容旧数据）
            log.warn("File {} is not valid base64, treating as text", fileName);
            return contents.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * 确保文件的所有父目录都被创建
     *
     * @param zos          ZipOutputStream
     * @param filePath     文件路径，如 "skills/skillName/abc/xxx.py"
     * @param baseDir      基础目录，如 "skills/skillName/"
     * @param addedEntries 已添加的条目集合
     */
    private void ensureParentDirectories(ZipOutputStream zos, String filePath, String baseDir, Set<String> addedEntries) throws IOException {
        // 移除基础目录前缀，获取相对路径
        String relativePath = filePath;
        if (filePath.startsWith(baseDir)) {
            relativePath = filePath.substring(baseDir.length());
        }

        // 如果路径中没有目录分隔符，说明文件在根目录下，不需要创建父目录
        if (!relativePath.contains("/")) {
            return;
        }

        // 提取所有父目录路径
        String[] parts = relativePath.split("/");
        StringBuilder currentPath = new StringBuilder(baseDir);

        // 遍历所有目录部分（除了最后一个文件名）
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i] == null || parts[i].isBlank()) {
                continue;
            }
            currentPath.append(parts[i]).append("/");
            String dirPath = currentPath.toString();

            // 如果目录还未添加，则创建它
            if (!addedEntries.contains(dirPath)) {
                ZipEntry dirEntry = new ZipEntry(dirPath);
                zos.putNextEntry(dirEntry);
                zos.closeEntry();
                addedEntries.add(dirPath);
            }
        }
    }

    private boolean isSuccess(Map<String, Object> result) {
        if (result == null) {
            return false;
        }
        Object successObj = result.get("success");
        return !(successObj instanceof Boolean) || (Boolean) successObj;
    }

    /**
     * 回退到 create-workspace 时，将 skillUrls 对应的技能文件下载解压后合并到 zip 的 skills/ 目录下
     */
    private MultipartFile buildZipWithSkillUrls(MultipartFile originalZipFile, List<String> skillUrls, boolean appendDynamicLockFile) {
        if (CollectionUtils.isEmpty(skillUrls)) {
            return originalZipFile;
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(); ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            Set<String> addedEntries = new HashSet<>();
            addDirectoryEntry(zos, "skills/", addedEntries);
            addDirectoryEntry(zos, "agents/", addedEntries);

            if (originalZipFile != null) {
                copyZipToOutput(originalZipFile.getBytes(), zos, addedEntries);
            }

            for (String skillUrl : skillUrls) {
                if (StringUtils.isBlank(skillUrl)) {
                    continue;
                }
                try (InputStream inputStream = new URL(skillUrl).openStream()) {
                    byte[] zipBytes = inputStream.readAllBytes();
                    copySkillZipToSkillsRoot(zipBytes, zos, addedEntries, appendDynamicLockFile);
                } catch (Exception e) {
                    log.error("[createWorkspace] 回退打包失败，下载或解压 skillUrl 异常, skillUrl={}", skillUrl, e);
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillZipPackFailed);
                }
            }

            zos.finish();
            byte[] zipBytes = baos.toByteArray();
            if (zipBytes.length == 0) {
                return null;
            }
            return new InMemoryMultipartFile("file", "skills.zip", "application/zip", zipBytes);
        } catch (IOException e) {
            log.error("[createWorkspace] 回退打包失败", e);
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillZipPackFailed);
        }
    }

    private void copyZipToOutput(byte[] sourceZipBytes, ZipOutputStream zos, Set<String> addedEntries) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(sourceZipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = normalizeZipPath(entry.getName());
                if (StringUtils.isBlank(entryName)) {
                    zis.closeEntry();
                    continue;
                }
                if (entry.isDirectory()) {
                    addDirectoryEntry(zos, entryName, addedEntries);
                } else {
                    byte[] data = zis.readAllBytes();
                    addFileEntry(zos, entryName, data, addedEntries);
                }
                zis.closeEntry();
            }
        }
    }

    private void copySkillZipToSkillsRoot(byte[] sourceZipBytes, ZipOutputStream zos, Set<String> addedEntries, boolean appendDynamicLockFile) throws IOException {
        Set<String> skillDirs = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(sourceZipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String rawName = normalizeZipPath(entry.getName());
                if (StringUtils.isBlank(rawName)) {
                    zis.closeEntry();
                    continue;
                }
                String targetName = rawName.startsWith("skills/") ? rawName : "skills/" + rawName;
                String skillDir = extractSkillDir(targetName);
                if (StringUtils.isNotBlank(skillDir)) {
                    skillDirs.add(skillDir);
                }
                if (entry.isDirectory()) {
                    addDirectoryEntry(zos, targetName, addedEntries);
                } else {
                    byte[] data = zis.readAllBytes();
                    addFileEntry(zos, targetName, data, addedEntries);
                }
                zis.closeEntry();
            }
        }
        if (appendDynamicLockFile) {
            byte[] lockFileBytes = "dynamic_add\n".getBytes(StandardCharsets.UTF_8);
            for (String skillDir : skillDirs) {
                addFileEntry(zos, skillDir + ".dynamic_add.lock", lockFileBytes, addedEntries);
            }
        }
    }

    private void addDirectoryEntry(ZipOutputStream zos, String dirName, Set<String> addedEntries) throws IOException {
        String normalizedDir = normalizeDirectoryPath(dirName);
        if (StringUtils.isBlank(normalizedDir) || addedEntries.contains(normalizedDir)) {
            return;
        }
        ZipEntry entry = new ZipEntry(normalizedDir);
        zos.putNextEntry(entry);
        zos.closeEntry();
        addedEntries.add(normalizedDir);
    }

    private void addFileEntry(ZipOutputStream zos, String entryName, byte[] data, Set<String> addedEntries) throws IOException {
        String normalizedPath = normalizeZipPath(entryName);
        if (StringUtils.isBlank(normalizedPath) || addedEntries.contains(normalizedPath)) {
            return;
        }
        ensureParentDirectories(zos, normalizedPath, addedEntries);
        ZipEntry entry = new ZipEntry(normalizedPath);
        zos.putNextEntry(entry);
        if (data != null && data.length > 0) {
            zos.write(data);
        }
        zos.closeEntry();
        addedEntries.add(normalizedPath);
    }

    private void ensureParentDirectories(ZipOutputStream zos, String filePath, Set<String> addedEntries) throws IOException {
        String normalizedPath = normalizeZipPath(filePath);
        if (StringUtils.isBlank(normalizedPath) || !normalizedPath.contains("/")) {
            return;
        }
        int index = normalizedPath.lastIndexOf('/');
        if (index <= 0) {
            return;
        }
        String[] parts = normalizedPath.substring(0, index).split("/");
        StringBuilder pathBuilder = new StringBuilder();
        for (String part : parts) {
            if (StringUtils.isBlank(part)) {
                continue;
            }
            pathBuilder.append(part).append("/");
            addDirectoryEntry(zos, pathBuilder.toString(), addedEntries);
        }
    }

    private String normalizeZipPath(String entryName) {
        if (entryName == null) {
            return "";
        }
        String normalized = entryName.replace("\\", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private String normalizeDirectoryPath(String dirName) {
        String normalized = normalizeZipPath(dirName);
        if (StringUtils.isBlank(normalized)) {
            return normalized;
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    private String extractSkillDir(String targetName) {
        String normalized = normalizeZipPath(targetName);
        if (StringUtils.isBlank(normalized) || !normalized.startsWith("skills/")) {
            return null;
        }
        String relative = normalized.substring("skills/".length());
        if (StringUtils.isBlank(relative)) {
            return null;
        }
        int index = relative.indexOf('/');
        if (index < 0) {
            return null;
        }
        String skillName = relative.substring(0, index);
        if (StringUtils.isBlank(skillName)) {
            return null;
        }
        return "skills/" + skillName + "/";
    }

    private SkillConfigDto parseSkillConfig(String config) {
        if (StringUtils.isBlank(config)) {
            return new SkillConfigDto();
        }
        try {
            SkillPublishedConfigDto publishedConfig = JSON.parseObject(config, SkillPublishedConfigDto.class);
            if (publishedConfig != null
                    && (SkillFileFormatConstants.SKILL_FILES_V2.equals(publishedConfig.getFormat()) || StringUtils.isNotBlank(publishedConfig.getZipFileUrl()))) {
                SkillConfigDto dto = new SkillConfigDto();
                dto.setId(publishedConfig.getId());
                dto.setName(publishedConfig.getName());
                dto.setDescription(publishedConfig.getDescription());
                dto.setIcon(publishedConfig.getIcon());
                dto.setFiles(publishedConfig.getFiles());
                dto.setZipFileUrl(publishedConfig.getZipFileUrl());
                return dto;
            }
        } catch (Exception e) {
            log.debug("parse skill config as v2 failed", e);
        }
        return JSON.parseObject(config, SkillConfigDto.class);
    }

    private boolean isV2Config(SkillConfigDto skillConfig) {
        return skillConfig != null && StringUtils.isNotBlank(skillConfig.getZipFileUrl());
    }

}
