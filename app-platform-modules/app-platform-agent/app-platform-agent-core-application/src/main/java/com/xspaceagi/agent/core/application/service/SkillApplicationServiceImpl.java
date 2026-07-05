package com.xspaceagi.agent.core.application.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.application.SkillApplicationService;
import com.xspaceagi.agent.core.adapter.constant.SkillFileFormatConstants;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.repository.CopyIndexRecordRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.ConfigHistory;
import com.xspaceagi.agent.core.adapter.repository.entity.PublishApply;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.SkillConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.UserTargetRelation;
import com.xspaceagi.agent.core.domain.service.ConfigHistoryDomainService;
import com.xspaceagi.agent.core.domain.service.PublishDomainService;
import com.xspaceagi.agent.core.domain.service.SkillDomainService;
import com.xspaceagi.agent.core.domain.service.UserTargetRelationDomainService;
import com.xspaceagi.agent.core.adapter.util.SkillNameUtil;
import com.xspaceagi.agent.core.spec.enums.UsageScenarioEnum;
import com.xspaceagi.agent.core.spec.utils.FileTypeUtils;
import com.xspaceagi.agent.core.spec.utils.MarkdownExtractUtil;
import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.file.InMemoryMultipartFile;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.xspaceagi.system.spec.utils.I18nUtil;
import com.xspaceagi.file.application.service.FileManagementService;
import com.xspaceagi.file.domain.model.FileRecordDomain;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class SkillApplicationServiceImpl implements SkillApplicationService {

    @Resource
    private SkillDomainService skillDomainService;
    @Resource
    private PublishDomainService publishDomainService;
    @Resource
    private CopyIndexRecordRepository copyIndexRecordRepository;
    @Resource
    private PublishApplicationService publishApplicationService;
    @Resource
    private ConfigHistoryDomainService configHistoryDomainService;
    @Resource
    private UserTargetRelationDomainService userTargetRelationDomainService;
    @Resource
    private SpaceApplicationService spaceApplicationService;
    @Resource
    private FileManagementService fileManagementService;
    @Resource
    private IFileAccessService iFileAccessService;

    public static final Long MAX_SKILL_FILE_SIZE = 100L * 1024 * 1024;

    // 单个文件最大大小100M
    public final static Long MAX_SINGLE_FILE_SIZE = 100 * 1024 * 1024L;
    private static final String TARGET_TYPE_SKILL_DEV = "skill_dev";
    private static final String TARGET_TYPE_SKILL_PUBLISH_APPLY = "skill_publish_apply";
    private static final String TARGET_TYPE_SKILL_PUBLISHED = "skill_published";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long add(SkillConfigDto skillConfigDto) {
        SkillConfig skillConfig = new SkillConfig();
        BeanUtils.copyProperties(skillConfigDto, skillConfig);
        if (StringUtils.isBlank(skillConfig.getName())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "skill name");
        }

        List<SkillFileDto> incomingFiles = normalizeSkillFiles(skillConfig.getFiles());
        skillConfig.setFiles(List.of());

        skillConfig.setPublishStatus(Published.PublishStatus.Developing);
        skillConfig.setTenantId(RequestContext.get().getTenantId());
        skillConfig.setCreatorId(RequestContext.get().getUserId());
        skillConfig.setCreatorName(RequestContext.get().getUserContext().getUserName());
        skillConfig.setYn(YnEnum.Y.getKey());

        skillConfig.setId(null);
        skillConfig.setCreated(null);
        skillConfig.setModified(null);
        skillConfig.setModifiedId(null);
        skillConfig.setModifiedName(null);

        Long skillId = skillDomainService.add(skillConfig);
        if (CollectionUtils.isNotEmpty(incomingFiles)) {
            SkillConfig updateConfig = new SkillConfig();
            updateConfig.setId(skillId);
            updateConfig.setFiles(uploadSkillFiles(incomingFiles, TARGET_TYPE_SKILL_DEV, skillId));
            skillDomainService.update(updateConfig);
        }
        addConfigHistory(skillId, ConfigHistory.Type.Add, I18nUtil.systemMessage("Skill.ConfigHistory.Add"));
        return skillId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(SkillConfigDto skillConfigDto, boolean isReplaceFiles) {
        // 这里查一遍，是为了取之前的 files，跟本次传入的files做比对
        SkillConfigDto exist = queryById(skillConfigDto.getId(), true);
        if (exist == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillNotFound);
        }

        SkillConfig skillConfig = new SkillConfig();
        BeanUtils.copyProperties(skillConfigDto, skillConfig);
        List<SkillFileDto> currentFiles = normalizeSkillFiles(exist.getFiles());
        boolean migratedLegacyFiles = false;
        if (!isReplaceFiles && containsLegacyInlineFiles(currentFiles)) {
            // 历史存量技能（files 里仍是 contents）在首次编辑时统一迁移到文件服务
            currentFiles = uploadSkillFiles(currentFiles, TARGET_TYPE_SKILL_DEV, skillConfigDto.getId());
            migratedLegacyFiles = true;
        }

        if (isReplaceFiles) { //替换
            deleteSkillFiles(exist.getFiles());
            List<SkillFileDto> replacement = normalizeSkillFiles(skillConfigDto.getFiles());
            skillConfig.setFiles(uploadSkillFiles(replacement, TARGET_TYPE_SKILL_DEV, skillConfigDto.getId()));
        } else { //更新
            if (CollectionUtils.isNotEmpty(skillConfigDto.getFiles())) {
                List<SkillFileDto> mergedFiles = parseFilesUpdate(currentFiles,
                        normalizeSkillFiles(skillConfigDto.getFiles()), skillConfigDto.getId());
                skillConfig.setFiles(mergedFiles);
            } else {
                // 无文件改动时，若刚发生了历史文件迁移，则也要回写新格式 files
                skillConfig.setFiles(migratedLegacyFiles ? currentFiles : null);
            }
        }
        skillDomainService.update(skillConfig);
        addConfigHistory(skillConfig.getId(), ConfigHistory.Type.Edit, I18nUtil.systemMessage("Skill.ConfigHistory.Edit"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long skillId) {
        if (skillId == null) {
            throw new IllegalArgumentException("Skill ID cannot be empty");
        }
        cleanupSkillStorageFiles(skillId);
        skillDomainService.delete(skillId);
    }

    @Override
    public SkillConfigDto queryById(Long skillId, boolean loadFiles) {
        SkillConfig skillConfig = skillDomainService.queryById(skillId, loadFiles);
        if (skillConfig == null || !YnEnum.isY(skillConfig.getYn())) {
            return null;
        }
        SkillConfigDto skillConfigDto = new SkillConfigDto();
        BeanUtils.copyProperties(skillConfig, skillConfigDto);
        skillConfigDto.setExt(convertToSkillExt(skillConfig.getExt()));
        return skillConfigDto;
    }

    @Override
    public SkillConfigDto queryById(Long skillId) {
        SkillConfig skillConfig = skillDomainService.queryById(skillId, true);
        if (skillConfig == null || !YnEnum.isY(skillConfig.getYn())) {
            return null;
        }
        SkillConfigDto skillConfigDto = new SkillConfigDto();
        BeanUtils.copyProperties(skillConfig, skillConfigDto);
        skillConfigDto.setExt(convertToSkillExt(skillConfig.getExt()));

        PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Skill, skillId, false);
        if (publishedDto != null) {
            skillConfigDto.setPublishDate(publishedDto.getModified());
            skillConfigDto.setScope(publishedDto.getScope());
            skillConfigDto.setCategory(publishedDto.getCategory());
            skillConfigDto.setPublishStatus(Published.PublishStatus.Published);
        }
        return skillConfigDto;
    }

    @Override
    public SkillConfigDto queryPublishedSkillConfig(Long skillId, Long spaceId, boolean loadFiles) {
        PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Skill, skillId, loadFiles);
        if (publishedDto == null) {
            return null;
        }
        if (spaceId != null && publishedDto.getPublishedSpaceIds() != null && !publishedDto.getPublishedSpaceIds().contains(spaceId)) {
            return null;
        }
        SkillConfigDto skillConfigDto = parsePublishedSkillConfig(publishedDto.getConfig(), publishedDto.getExt());
        if (skillConfigDto == null) {
            skillConfigDto = new SkillConfigDto();
            skillConfigDto.setId(skillId);
            skillConfigDto.setName(publishedDto.getName());
            skillConfigDto.setDescription(publishedDto.getDescription());
            skillConfigDto.setIcon(publishedDto.getIcon());
//            skillConfigDto.setCreatorId(publishedDto.getPublishUser().getUserId());
        }
        skillConfigDto.setPublishDate(publishedDto.getModified());
        skillConfigDto.setPublishedSpaceIds(publishedDto.getPublishedSpaceIds());
        return skillConfigDto;
    }

    @Override
    public List<SkillConfigDto> queryUserRelatedPublishedSkillConfigs(Long userId, List<Long> skillIds) {
        List<SkillConfigDto> result = new ArrayList<>();
        if (CollectionUtils.isEmpty(skillIds)) {
            return result;
        }
        // 获取用户有权限的空间id列表
        Set<Long> spaceIds = spaceApplicationService.queryListByUserId(userId).stream().map(SpaceDto::getId).collect(Collectors.toSet());
        skillIds.forEach(skillId -> {
            PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Skill, skillId, true);
            if (publishedDto == null) {
                return;
            }
            SkillConfigDto skillConfigDto = parsePublishedSkillConfig(publishedDto.getConfig(), publishedDto.getExt());
            if (skillConfigDto == null || CollectionUtils.isEmpty(skillConfigDto.getFiles())) {
                return;
            }
            if (publishedDto.getScope() != Published.PublishScope.Tenant && publishedDto.getPublishedSpaceIds().stream().noneMatch(spaceIds::contains)) {
                log.info("用户 {} 无权限技能：{}", userId, skillId);
                return;
            }
            SkillNameUtil.backfillName(skillConfigDto, iFileAccessService);
            result.add(skillConfigDto);
        });
        return result;
    }

    @Override
    public List<SkillConfigDto> queryList(SkillQueryDto queryDto) {
        LambdaQueryWrapper<SkillConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper
                // 列表查询不需要 files，大字段从 SQL 层面排除，避免反序列化开销
                .select(SkillConfig.class, info -> !"files".equals(info.getColumn()))
                .eq(SkillConfig::getSpaceId, queryDto.getSpaceId())
                .like(queryDto.getName() != null && !queryDto.getName().isBlank(), SkillConfig::getName, queryDto.getName())
                .in(queryDto.getPublishStatus() != null, SkillConfig::getPublishStatus, queryDto.getPublishStatus())
                .eq(SkillConfig::getYn, YnEnum.Y.getKey())
                .orderByDesc(SkillConfig::getModified);
        List<UsageScenarioEnum> usageScenarios = queryDto.getUsageScenarios();
        if (usageScenarios != null && !usageScenarios.isEmpty()) {
            if (usageScenarios.contains(UsageScenarioEnum.TaskAgent)) {
                queryWrapper.apply("(CAST(JSON_UNQUOTE(JSON_EXTRACT(ext, '$.supportTaskAgent')) AS UNSIGNED) = 1 OR ext IS NULL)");
            }
            if (usageScenarios.contains(UsageScenarioEnum.PageApp)) {
                queryWrapper.apply("CAST(JSON_UNQUOTE(JSON_EXTRACT(ext, '$.supportPageApp')) AS UNSIGNED) = 1");
            }
        }

        List<SkillConfig> skillConfigs = skillDomainService.list(queryWrapper);

        if (CollectionUtils.isEmpty(skillConfigs)) {
            return List.of();
        }

        return skillConfigs.stream().map(skillConfig -> {
            SkillConfigDto skillConfigDto = new SkillConfigDto();
            BeanUtils.copyProperties(skillConfig, skillConfigDto);
            skillConfigDto.setExt(convertToSkillExt(skillConfig.getExt()));
            // 列表查询不返回 files，避免大字段影响性能
            skillConfigDto.setFiles(null);
            return skillConfigDto;
        }).collect(Collectors.toList());
    }

    private SkillExtDto convertToSkillExt(Object ext) {
        if (ext == null) {
            return null;
        }
        if (ext instanceof SkillExtDto skillExtDto) {
            return skillExtDto;
        }
        try {
            return JSON.parseObject(JSON.toJSONString(ext), SkillExtDto.class);
        } catch (Exception e) {
            log.warn("Failed to convert skill ext to SkillExtDto, ext={}", ext, e);
            return null;
        }
    }

    @Override
    public SkillExportResultDto exportSkill(Long skillId) {
        SkillConfigDto skillConfigDto = queryById(skillId, true);
        return exportSkill(skillConfigDto);
    }

    @Override
    public SkillExportResultDto exportSkill(SkillConfigDto skillConfigDto) {
        if (skillConfigDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "skill");
        }
        if (StringUtils.isBlank(skillConfigDto.getName())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "skill name");
        }
        String folderName = skillConfigDto.getName();
        String fileName = folderName + ".zip";

        byte[] bytes = buildSkillZipBytes(skillConfigDto, folderName, false);

        SkillExportResultDto result = new SkillExportResultDto();
        result.setFileName(fileName);
        result.setContentType("application/zip");
        result.setData(bytes);
        return result;
    }


    @Override
    public Long importSkill(MultipartFile file, SkillConfigDto existSkill, Long targetSpaceId, SkillExtDto ext) {
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillUploadFileRequired);
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, I18nUtil.systemMessage("Skill.Import.FileName"));
        }
        String fileNameLower = fileName.toLowerCase();
        boolean isZipLike = fileNameLower.endsWith(".zip") || fileNameLower.endsWith(".skill");
        boolean isSingleSkillMd = "skill.md".equals(fileNameLower);

        if (!isZipLike && !isSingleSkillMd) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillFileFormatInvalid);
        }

        if (existSkill == null && targetSpaceId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillTargetSpaceRequired);
        }

        SkillConfigDto skillConfigDto = new SkillConfigDto();
        List<SkillFileDto> files = new ArrayList<>();
        SkillConfigDto metaDto = null;
        // SKILL.md 校验相关
        boolean hasSkillMd = false;
        String skillMdContent = null;

        if (isZipLike) {
            // 用于存储所有条目信息，以便分析顶层目录
            List<ZipEntryInfo> entryInfos = new ArrayList<>();
            // 用于收集有问题的文件信息
            List<String> errorFiles = new ArrayList<>();

            try (ZipInputStream zipInputStream = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                int entryIndex = 0; // 用于记录条目索引，当无法获取文件名时使用
                while (true) {
                    try {
                        entry = zipInputStream.getNextEntry();
                        if (entry == null) {
                            break;
                        }
                        entryIndex++;
                    } catch (ZipException e) {
                        // 处理不支持的 ZIP 压缩方法（如 STORED 方法带 EXT descriptor）
                        // 记录错误信息，继续处理下一个
                        entryIndex++;
                        String errorMsg = String.format(I18nUtil.systemMessage("Skill.Import.ZipEntryError"), entryIndex, e.getMessage());
                        errorFiles.add(errorMsg);
                        log.warn("ZIP 条目解析失败: {}", errorMsg);
                        continue;
                    }

                    String entryName = entry.getName();
                    // 过滤掉 macOS 系统文件（如 __MACOSX/ 目录、.DS_Store 等）
                    if (shouldSkipFile(entryName)) {
                        try {
                            zipInputStream.closeEntry();
                        } catch (Exception e) {
                            log.warn("Failed to close ZIP entry: {}", entryName, e);
                        }
                        continue;
                    }

                    boolean isDirectory = entry.isDirectory();
                    String content = null;

                    if (!isDirectory) {
                        try {
                            // 检查压缩方法，跳过不支持的方法
                            int method = entry.getMethod();
                            // ZipEntry.STORED = 0, ZipEntry.DEFLATED = 8
                            // 如果是不支持的方法，记录错误
                            if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
                                String errorMsg = String.format(I18nUtil.systemMessage("Skill.Import.UnsupportedCompressionMethod"), entryName, method);
                                errorFiles.add(errorMsg);
                                log.warn("不支持的压缩方法: {}", errorMsg);
                                try {
                                    zipInputStream.closeEntry();
                                } catch (Exception e) {
                                    // 忽略关闭异常
                                }
                                continue;
                            }

                            // 校验单个文件大小（如果 ZIP 元数据中已提供）
                            long entrySize = entry.getSize();
                            if (entrySize > MAX_SINGLE_FILE_SIZE) {
                                try {
                                    zipInputStream.closeEntry();
                                } catch (Exception e) {
                                    // 忽略关闭异常
                                }
                                double entrySizeMB = entrySize / (1024.0 * 1024.0);
                                double maxSizeMB = MAX_SINGLE_FILE_SIZE / (1024.0 * 1024.0);
                                String errorMsg = String.format(I18nUtil.systemMessage("Skill.Import.FileSizeExceeded"), entryName, entrySizeMB, maxSizeMB);
                                errorFiles.add(errorMsg);
                                // 文件大小超限是严重错误，继续收集其他错误后统一抛出
                                continue;
                            }

                            // 判断是否为文本文件
                            if (FileTypeUtils.isTextFile(entryName)) {
                                // 文本文件直接读取
                                content = readZipEntryContent(zipInputStream);
                            } else {
                                // 非文本文件（二进制）转换为 base64
                                byte[] bytes = readZipEntryBytes(zipInputStream);
                                content = Base64.getEncoder().encodeToString(bytes);
                            }
                        } catch (ZipException e) {
                            // 处理读取条目内容时的 ZIP 异常，记录错误信息
                            String errorMsg = String.format(I18nUtil.systemMessage("Skill.Import.ReadFileError"), entryName, e.getMessage());
                            errorFiles.add(errorMsg);
                            log.warn("读取 ZIP 条目内容失败: {}", errorMsg);
                            try {
                                zipInputStream.closeEntry();
                            } catch (Exception ex) {
                                // 忽略关闭异常
                            }
                            continue;
                        } catch (BizException e) {
                            // 文件大小超限等业务异常，记录错误信息
                            String errorMsg = String.format(I18nUtil.systemMessage("Skill.Import.ReadFileError"), entryName, e.getMessage());
                            errorFiles.add(errorMsg);
                            try {
                                zipInputStream.closeEntry();
                            } catch (Exception ex) {
                                // 忽略关闭异常
                            }
                            continue;
                        } catch (Exception e) {
                            // 其他读取异常，记录错误信息
                            String errorMsg = String.format(I18nUtil.systemMessage("Skill.Import.ReadFileError"), entryName, e.getMessage());
                            errorFiles.add(errorMsg);
                            log.warn("读取 ZIP 条目失败: {}", errorMsg);
                            try {
                                zipInputStream.closeEntry();
                            } catch (Exception ex) {
                                // 忽略关闭异常
                            }
                            continue;
                        }
                    }

                    entryInfos.add(new ZipEntryInfo(entryName, isDirectory, content));
                    try {
                        zipInputStream.closeEntry();
                    } catch (Exception e) {
                        log.warn("Failed to close ZIP entry: {}", entryName, e);
                    }
                }

                // 如果有错误文件，统一抛出异常
                if (!errorFiles.isEmpty()) {
                    StringBuilder errorMessage = new StringBuilder(I18nUtil.systemMessage("Skill.Import.ErrorFilesHeader"));
                    for (int i = 0; i < errorFiles.size(); i++) {
                        errorMessage.append(String.format("%d. %s\n", i + 1, errorFiles.get(i)));
                    }
                    errorMessage.append("\n").append(I18nUtil.systemMessage("Skill.Import.ErrorFilesFooter"));
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                            errorMessage.toString());
                }

            } catch (BizException e) {
                log.error("Failed to parse skill import file", e);
                throw e;
            } catch (Exception e) {
                log.error("Failed to parse skill import file", e);
                // 如果是 ZipException，提供更具体的错误信息
                if (e instanceof ZipException) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillZipFormatInvalid,
                            e.getMessage());
                }
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillFileFormatError,
                        e.getMessage());
            }

            // 找到共同的顶层目录前缀
            String topLevelPrefix = findTopLevelPrefix(entryInfos);

            // 处理所有条目，去掉顶层目录前缀
            for (ZipEntryInfo entryInfo : entryInfos) {
                String entryName = entryInfo.getName();

                boolean isDirectory = entryInfo.isDirectory();

                // 去掉顶层目录前缀
                String processedPath = entryName;
                if (topLevelPrefix != null && processedPath.startsWith(topLevelPrefix)) {
                    processedPath = processedPath.substring(topLevelPrefix.length());
                }

                // 记录根目录下的 SKILL.md，用于必填校验
                if ("SKILL.md".equalsIgnoreCase(processedPath)) {
                    hasSkillMd = true;
                    skillMdContent = entryInfo.getContent();
                }

                if (processedPath.toLowerCase().endsWith(".skill.json")) {
                    metaDto = JSON.parseObject(entryInfo.getContent(), SkillConfigDto.class);
                } else {
                    String filePath = processedPath;

                    // 目录路径去掉末尾的/
                    if (isDirectory && filePath.endsWith("/")) {
                        filePath = filePath.substring(0, filePath.length() - 1);
                    }

                    if (!filePath.isBlank()) {
                        SkillFileDto fileDto = new SkillFileDto();
                        // 根目录下的 SKILL.md 文件名统一（区分大小写）
                        if (!isDirectory && "SKILL.md".equalsIgnoreCase(filePath)) {
                            fileDto.setName("SKILL.md");
                        } else {
                            fileDto.setName(filePath);
                        }
                        fileDto.setIsDir(isDirectory);
                        if (!isDirectory) {
                            fileDto.setContents(entryInfo.getContent());
                        }
                        files.add(fileDto);
                    }
                }
            }
        } else if (isSingleSkillMd) {
            // 仅上传单个 SKILL.md 文件的场景
            try {
                byte[] fileBytes = file.getBytes();
                if (fileBytes.length > MAX_SINGLE_FILE_SIZE) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillFileSizeExceeded100m);
                }
                // SKILL.md 作为文本文件处理
                skillMdContent = new String(fileBytes, StandardCharsets.UTF_8);
                hasSkillMd = true;

                SkillFileDto fileDto = new SkillFileDto();
                // 最终存储文件名统一为 SKILL.md（区分大小写）
                fileDto.setName("SKILL.md");
                fileDto.setIsDir(false);
                fileDto.setContents(skillMdContent);
                files.add(fileDto);
            } catch (IOException e) {
                log.error("读取 SKILL.md 文件失败", e);
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillReadSkillMdFailed);
            }
        }

        // 校验 SKILL.md 是否存在
        if (!hasSkillMd) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillMdRequired);
        }

        // 从 SKILL.md 中解析 name 和 description
        String nameInMd = MarkdownExtractUtil.extractFieldValue(skillMdContent, "name");
        String descriptionInMd = MarkdownExtractUtil.extractFieldValue(skillMdContent, "description");

        if (StringUtils.isAnyBlank(nameInMd, descriptionInMd)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillMdNameDescRequired);
        }

        skillConfigDto.setFiles(files);
        skillConfigDto.setName(nameInMd);
        skillConfigDto.setDescription(descriptionInMd);

        if (existSkill != null) {
            skillConfigDto.setId(existSkill.getId());
            skillConfigDto.setSpaceId(existSkill.getSpaceId());
        } else {
            // 创建新技能设置扩展字段，如果是原项目中导入，则使用原技能的扩展字段
            skillConfigDto.setExt(ext);
            skillConfigDto.setSpaceId(targetSpaceId);
            skillConfigDto.setIcon(metaDto != null ? metaDto.getIcon() : null);
        }

        Long resultId = null;
        if (existSkill == null) {
            resultId = this.add(skillConfigDto);
        } else {
            this.update(skillConfigDto, true);
            resultId = existSkill.getId();
        }
        return resultId;
    }

    @Override
    public Long importSkill(String url, MultipartFile file, Long targetSkillId, Long targetSpaceId, List<UsageScenarioEnum> usageScenarios) {
        // file 和 url 二选一
        if ((file == null || file.isEmpty()) && (url == null || url.isBlank())) {
            throw new IllegalArgumentException("Please provide either a file or a URL to import");
        }
        if (file != null && !file.isEmpty() && url != null && !url.isBlank()) {
            throw new IllegalArgumentException("Please provide either a file or a URL, not both");
        }

        // 如果提供了 url，下载文件
        if ((file == null || file.isEmpty()) && url != null && !url.isBlank()) {
            file = downloadFromUrl(url);
        }

        if (file.isEmpty()) {
            throw new IllegalArgumentException("Please select a file to upload");
        }

        // 检查文件大小
        if (file.getSize() > MAX_SKILL_FILE_SIZE) {
            throw new IllegalArgumentException("Import file must be smaller than 20 MB");
        }

        // 查询已有技能
        SkillConfigDto existSkill = null;
        if (targetSkillId != null) {
            existSkill = this.queryById(targetSkillId, true);
            if (existSkill == null) {
                throw new IllegalArgumentException("Skill does not exist");
            }
        } else {
            if (targetSpaceId == null) {
                throw new IllegalArgumentException("Please select a target space");
            }
        }

        // 转换 usageScenarios 为 SkillExtDto
        SkillExtDto ext = convertUsageScenariosToExt(usageScenarios);

        return this.importSkill(file, existSkill, targetSpaceId, ext);
    }

    /**
     * 从 URL 下载技能文件，返回 MultipartFile
     */
    private MultipartFile downloadFromUrl(String url) {
        try {
            String decodedUrl = URLDecoder.decode(url.trim(), StandardCharsets.UTF_8);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(decodedUrl))
                    .GET()
                    .build();
            HttpClient httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalArgumentException("Failed to download skill file from URL, HTTP status: " + response.statusCode());
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                throw new IllegalArgumentException("Downloaded file is empty");
            }
            // 从 URL 路径中提取文件名
            String path = URI.create(decodedUrl).getPath();
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            if (fileName.isBlank()) {
                fileName = "skill.zip";
            }
            return new InMemoryMultipartFile("file", fileName, "application/octet-stream", body);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to download skill file from URL: {}", url, e);
            throw new IllegalArgumentException("Failed to download skill file from URL: " + e.getMessage());
        }
    }

    private SkillExtDto convertUsageScenariosToExt(List<UsageScenarioEnum> usageScenarios) {
        if (usageScenarios == null || usageScenarios.isEmpty()) {
            SkillExtDto ext = new SkillExtDto();
            ext.setSupportTaskAgent(1);
            ext.setSupportPageApp(0);
            return ext;
        }
        SkillExtDto ext = new SkillExtDto();
        ext.setSupportTaskAgent(usageScenarios.contains(UsageScenarioEnum.TaskAgent) ? 1 : 0);
        ext.setSupportPageApp(usageScenarios.contains(UsageScenarioEnum.PageApp) ? 1 : 0);
        return ext;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long copySkill(SkillConfigDto skillConfigDto, Long targetSpaceId) {
        String newName = copyIndexRecordRepository.newCopyName("skill", skillConfigDto.getSpaceId(), skillConfigDto.getName());
        skillConfigDto.setName(newName);
        skillConfigDto.setSpaceId(targetSpaceId);
        return this.add(skillConfigDto);
    }

    @Override
    public SkillConfigDto getSkillTemplate(InputStream inputStream) {
        ObjectMapper objectMapper = new ObjectMapper();
        StringBuilder stringBuilder = new StringBuilder();
        TypeReference<List<SkillFileDto>> typeReference = new TypeReference<>() {
        };

        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line).append("\n");
            }
            String jsonContent = stringBuilder.toString();

            List<SkillFileDto> files = objectMapper.readValue(jsonContent, typeReference);
            SkillConfigDto skillConfigDto = new SkillConfigDto();
            skillConfigDto.setFiles(files);
            skillConfigDto.setName("Template");
            skillConfigDto.setDescription("Template");
            skillConfigDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(null, "Template", "skill"));
            return skillConfigDto;
        } catch (Exception e) {
            log.error("Failed to read skill template", e);
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillTemplateReadFailed,
                    e.getMessage());
        }
    }

    @Override
    public SkillConfigDto getSkillTemplate() {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("templates/skill-template.json")) {
            if (inputStream == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillTemplateReadFailed,
                        "skill template not found");
            }
            return getSkillTemplate(inputStream);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillTemplateReadFailed, e.getMessage());
        }
    }

    @Override
    public void checkSpaceSkillPermission(Long spaceId, Long skillId) {
        SkillConfigDto skillConfigDto = queryById(skillId, false);
        if (skillConfigDto != null && skillConfigDto.getSpaceId().equals(spaceId)) {
            // 同空间的技能有权限
            return;
        }
        // 检查已发布的技能
        SkillConfigDto publishedSkill = queryPublishedSkillConfig(skillId, spaceId, false);
        if (publishedSkill == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillNotFoundOrDenied);
        }
    }

    @Override
    public SkillFileDto processUploadFile(MultipartFile file, String filePath, Long skillId) {
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillUploadFileRequired);
        }
        if (skillId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillNotFound);
        }
        if (filePath == null || filePath.trim().isEmpty()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillFilePathInvalid);
        }

        try {
            byte[] fileBytes = file.getBytes();
            if (fileBytes.length > MAX_SINGLE_FILE_SIZE) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillFileSizeExceeded100m);
            }

            String normalizedPath = normalizePath(filePath, false);
            if (StringUtils.isBlank(normalizedPath)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillFilePathInvalid);
            }
            FileRecordDomain fileRecord = uploadBytes(fileBytes, normalizedPath, TARGET_TYPE_SKILL_DEV, skillId);
            SkillFileDto fileDto = new SkillFileDto();
            fileDto.setName(normalizedPath);
            fileDto.setIsDir(false);
            fileDto.setFileProxyUrl(fileRecord.getFileUrl());
            return fileDto;
        } catch (IOException e) {
            log.error("处理上传文件失败", e);
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillUploadProcessFailed,
                    e.getMessage());
        }
    }

    @Override
    public void saveRecentlyUsedSkills(List<Long> skillIds) {
        Long userId = RequestContext.get() != null ? RequestContext.get().getUserId() : null;
        if (userId == null) {
            throw BizException.of(ErrorCodeEnum.UNAUTHORIZED, BizExceptionCodeEnum.userNotLoggedIn);
        }
        List<Long> targetIds = skillIds.stream().filter(Objects::nonNull).toList();
        if (CollectionUtils.isEmpty(targetIds)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "skillIds");
        }
        for (Long skillId : targetIds) {
            UserTargetRelation userTargetRelation = new UserTargetRelation();
            userTargetRelation.setUserId(userId);
            userTargetRelation.setTargetId(skillId);
            userTargetRelation.setType(UserTargetRelation.OpType.Conversation);
            userTargetRelation.setTargetType(Published.TargetType.Skill);
            userTargetRelationDomainService.addOrUpdateRecentUsed(userTargetRelation);
        }
    }

    @Override
    public List<PublishedDto> queryRecentlyUsedSkills(String kw, Integer size) {
        Long userId = RequestContext.get() != null ? RequestContext.get().getUserId() : null;
        if (userId == null) {
            throw BizException.of(ErrorCodeEnum.UNAUTHORIZED, BizExceptionCodeEnum.userNotLoggedIn);
        }
        List<UserTargetRelation> userTargetRelations = userTargetRelationDomainService.queryRecentUseList(userId, Published.TargetType.Skill, size, 1);
        if (CollectionUtils.isEmpty(userTargetRelations)) {
            return null;
        }
        List<Long> targetIdList = userTargetRelations.stream().map(UserTargetRelation::getTargetId).toList();
        if (CollectionUtils.isEmpty(targetIdList)) {
            return null;
        }
        List<Published> publishedList = publishDomainService.queryPublishedListWithoutConfig(Published.TargetType.Skill, targetIdList);
        if (CollectionUtils.isEmpty(publishedList)) {
            return null;
        }
        if (StringUtils.isNotBlank(kw)) {
            publishedList = publishedList.stream().filter(publishedDto -> publishedDto.getName().contains(kw)).toList();
        }
        List<PublishedDto> publishedDtos = List.of();
        if (CollectionUtils.isNotEmpty(publishedList)) {
            //去重
            publishedList = publishedList.stream().collect(Collectors.collectingAndThen(Collectors.toCollection(() -> new TreeSet<>(Comparator.comparing(Published::getTargetId))), ArrayList::new));

            publishedDtos = publishedList.stream().map(published -> {
                PublishedDto publishedDto = new PublishedDto();
                BeanUtils.copyProperties(published, publishedDto);
                publishedDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(publishedDto.getIcon(), publishedDto.getName(), Published.TargetType.Skill.name()));
                return publishedDto;
            }).toList();
        }
        return publishedDtos;
    }

    //
    // ------------------ 以下是private方法 -----------------------
    //

    private List<SkillFileDto> normalizeSkillFiles(List<SkillFileDto> files) {
        if (CollectionUtils.isEmpty(files)) {
            return new ArrayList<>();
        }
        Map<String, SkillFileDto> deduplicated = new LinkedHashMap<>();
        for (SkillFileDto file : files) {
            if (file == null || StringUtils.isBlank(file.getName())) {
                continue;
            }
            String normalizedName = normalizePath(file.getName(), Boolean.TRUE.equals(file.getIsDir()));
            if (StringUtils.isBlank(normalizedName)) {
                continue;
            }
            SkillFileDto normalized = new SkillFileDto();
            normalized.setName(normalizedName);
            normalized.setIsDir(Boolean.TRUE.equals(file.getIsDir()));
            normalized.setContents(file.getContents());
            normalized.setFileProxyUrl(file.getFileProxyUrl());
            normalized.setOperation(file.getOperation());
            normalized.setRenameFrom(file.getRenameFrom());
            deduplicated.put(normalizedName, normalized);
        }
        return compactDirectoryEntries(new ArrayList<>(deduplicated.values()));
    }

    private boolean containsLegacyInlineFiles(List<SkillFileDto> files) {
        if (CollectionUtils.isEmpty(files)) {
            return false;
        }
        return files.stream().anyMatch(file ->
                file != null
                        && !Boolean.TRUE.equals(file.getIsDir())
                        && StringUtils.isBlank(file.getFileProxyUrl())
                        && StringUtils.isNotBlank(file.getContents()));
    }

    /**
     * 压缩目录索引：若目录下存在任意子文件/子目录，则该父目录可由路径隐式推导，无需单独存储。
     * 仅保留“叶子目录/空目录”，避免 import 后出现冗余父目录索引。
     */
    private List<SkillFileDto> compactDirectoryEntries(List<SkillFileDto> files) {
        if (CollectionUtils.isEmpty(files)) {
            return new ArrayList<>();
        }
        Set<String> allPaths = files.stream()
                .filter(Objects::nonNull)
                .map(SkillFileDto::getName)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());

        List<SkillFileDto> result = new ArrayList<>();
        for (SkillFileDto file : files) {
            if (file == null || StringUtils.isBlank(file.getName())) {
                continue;
            }
            if (!Boolean.TRUE.equals(file.getIsDir())) {
                result.add(file);
                continue;
            }
            String dirPrefix = file.getName() + "/";
            boolean hasChild = allPaths.stream()
                    .anyMatch(path -> !path.equals(file.getName()) && path.startsWith(dirPrefix));
            if (!hasChild) {
                result.add(file);
            }
        }
        return result;
    }

    private String normalizePath(String path, boolean isDir) {
        if (StringUtils.isBlank(path)) {
            return path;
        }
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (isDir && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private List<SkillFileDto> uploadSkillFiles(List<SkillFileDto> files, String targetType, Long skillId) {
        if (CollectionUtils.isEmpty(files)) {
            return new ArrayList<>();
        }
        List<SkillFileDto> uploadedFiles = new ArrayList<>();
        for (SkillFileDto file : files) {
            SkillFileDto uploaded = uploadOrBuildDirectory(file, targetType, skillId);
            if (uploaded != null) {
                uploadedFiles.add(uploaded);
            }
        }
        return uploadedFiles;
    }

    private SkillFileDto uploadOrBuildDirectory(SkillFileDto file, String targetType, Long skillId) {
        if (file == null || StringUtils.isBlank(file.getName())) {
            return null;
        }
        SkillFileDto result = new SkillFileDto();
        result.setName(file.getName());
        result.setIsDir(Boolean.TRUE.equals(file.getIsDir()));
        if (Boolean.TRUE.equals(file.getIsDir())) {
            return result;
        }
        byte[] bytes = resolveFileBytesForUpload(file);
        FileRecordDomain fileRecord = uploadBytes(bytes, file.getName(), targetType, skillId);
        result.setFileProxyUrl(fileRecord.getFileUrl());
        return result;
    }

    private SkillFileDto renameSkillFile(SkillFileDto file, String newName, String targetType, Long skillId) {
        if (Boolean.TRUE.equals(file.getIsDir())) {
            SkillFileDto dir = new SkillFileDto();
            dir.setName(newName);
            dir.setIsDir(true);
            return dir;
        }
        byte[] bytes = resolveFileBytesForUpload(file);
        FileRecordDomain fileRecord = uploadBytes(bytes, newName, targetType, skillId);
        deleteSkillFile(file);
        SkillFileDto renamed = new SkillFileDto();
        renamed.setName(newName);
        renamed.setIsDir(false);
        renamed.setFileProxyUrl(fileRecord.getFileUrl());
        return renamed;
    }

    private FileRecordDomain uploadBytes(byte[] bytes, String path, String targetType, Long skillId) {
        String fileName = path;
        int idx = path.lastIndexOf('/');
        if (idx >= 0 && idx < path.length() - 1) {
            fileName = path.substring(idx + 1);
        }
        String contentType = FileTypeUtils.getContentTypeByFileName(fileName).toString();
        InMemoryMultipartFile multipartFile = new InMemoryMultipartFile("file", fileName, contentType, bytes);
        Long tenantId = RequestContext.get() != null ? RequestContext.get().getTenantId() : null;
        Long userId = RequestContext.get() != null ? RequestContext.get().getUserId() : null;
        return fileManagementService.uploadFile(multipartFile, tenantId, userId, targetType, skillId, path, true);
    }

    private byte[] resolveFileBytesForUpload(SkillFileDto file) {
        if (StringUtils.isNotBlank(file.getContents())) {
            return getFileBytes(file.getContents(), file.getName());
        }
        if (StringUtils.isNotBlank(file.getFileProxyUrl())) {
            return downloadFileBytes(file.getFileProxyUrl());
        }
        return new byte[0];
    }

    private byte[] downloadFileBytes(String fileProxyUrl) {
        String fileKey = extractFileKey(fileProxyUrl);
        if (StringUtils.isNotBlank(fileKey)) {
            try (InputStream inputStream = fileManagementService.downloadFile(fileKey)) {
                if (inputStream != null) {
                    return readAllBytes(inputStream);
                }
            } catch (Exception e) {
                log.warn("download file by key failed, key={}", fileKey, e);
            }
        }
        try (InputStream inputStream = new URI(fileProxyUrl).toURL().openStream()) {
            return readAllBytes(inputStream);
        } catch (Exception e) {
            log.warn("download file by url failed, url={}", fileProxyUrl, e);
            return new byte[0];
        }
    }

    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, len);
        }
        return outputStream.toByteArray();
    }

    private void deleteSkillFiles(List<SkillFileDto> files) {
        if (CollectionUtils.isEmpty(files)) {
            return;
        }
        files.forEach(this::deleteSkillFile);
    }

    private void deleteSkillFile(SkillFileDto file) {
        if (file == null || Boolean.TRUE.equals(file.getIsDir()) || StringUtils.isBlank(file.getFileProxyUrl())) {
            return;
        }
        String fileKey = extractFileKey(file.getFileProxyUrl());
        if (StringUtils.isBlank(fileKey)) {
            return;
        }
        try {
            FileRecordDomain fileRecord = fileManagementService.getFileByKey(fileKey);
            if (fileRecord == null && fileKey.startsWith("/")) {
                fileRecord = fileManagementService.getFileByKey(fileKey.substring(1));
            }
            if (fileRecord != null && fileRecord.getId() != null) {
                fileManagementService.deleteFile(fileRecord.getId());
            }
        } catch (Exception e) {
            log.warn("delete skill file failed, key={}", fileKey, e);
        }
    }

    private void cleanupSkillStorageFiles(Long skillId) {
        Long tenantId = RequestContext.get() != null ? RequestContext.get().getTenantId() : null;
        deleteFilesByTarget(tenantId, TARGET_TYPE_SKILL_DEV, skillId);
        cleanupApplyingPublishApplyFiles(skillId);
        deleteFilesByTarget(tenantId, TARGET_TYPE_SKILL_PUBLISHED, skillId);
    }

    private void deleteFilesByTarget(Long tenantId, String targetType, Long targetId) {
        try {
            List<FileRecordDomain> files = fileManagementService.listTargetFiles(tenantId, targetType, targetId);
            if (CollectionUtils.isEmpty(files)) {
                return;
            }
            for (FileRecordDomain file : files) {
                if (file == null || file.getId() == null) {
                    continue;
                }
                fileManagementService.deleteFile(file.getId());
            }
        } catch (Exception e) {
            log.warn("cleanup skill storage files failed, targetType={}, targetId={}", targetType, targetId, e);
        }
    }

    private void cleanupApplyingPublishApplyFiles(Long skillId) {
        try {
            List<PublishApply> applyList = publishDomainService.queryPublishApplyList(Published.TargetType.Skill, skillId);
            if (CollectionUtils.isEmpty(applyList)) {
                return;
            }
            Set<String> fileProxyUrls = new HashSet<>();
            for (PublishApply apply : applyList) {
                if (apply == null || apply.getPublishStatus() != Published.PublishStatus.Applying || StringUtils.isBlank(apply.getConfig())) {
                    continue;
                }
                try {
                    List<SkillFileDto> files = JSON.parseArray(apply.getConfig(), SkillFileDto.class);
                    if (CollectionUtils.isEmpty(files)) {
                        continue;
                    }
                    files.stream()
                            .filter(Objects::nonNull)
                            .map(SkillFileDto::getFileProxyUrl)
                            .filter(StringUtils::isNotBlank)
                            .forEach(fileProxyUrls::add);
                } catch (Exception e) {
                    log.warn("parse publish_apply config failed when cleanup, applyId={}", apply.getId(), e);
                }
            }

            for (String fileProxyUrl : fileProxyUrls) {
                String fileKey = extractFileKey(fileProxyUrl);
                if (StringUtils.isBlank(fileKey)) {
                    continue;
                }
                FileRecordDomain fileRecord = fileManagementService.getFileByKey(fileKey);
                if (fileRecord == null && fileKey.startsWith("/")) {
                    fileRecord = fileManagementService.getFileByKey(fileKey.substring(1));
                }
                if (fileRecord != null && fileRecord.getId() != null) {
                    fileManagementService.deleteFile(fileRecord.getId());
                }
            }
        } catch (Exception e) {
            log.warn("cleanup applying publish_apply files failed, skillId={}", skillId, e);
        }
    }

    private String extractFileKey(String fileProxyUrl) {
        if (StringUtils.isBlank(fileProxyUrl)) {
            return null;
        }
        String value = fileProxyUrl;
        int queryIdx = value.indexOf('?');
        if (queryIdx > -1) {
            value = value.substring(0, queryIdx);
        }
        int markerIdx = value.indexOf("/api/f/");
        if (markerIdx > -1) {
            return value.substring(markerIdx + "/api/f/".length());
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    @Override
    public SkillConfigDto parsePublishedSkillConfig(String config, Object ext) {
        if (StringUtils.isBlank(config)) {
            return null;
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
                if (ext != null) {
                    dto.setExt(parseSkillExtDto(ext));
                }
                dto.setFiles(normalizeSkillFiles(publishedConfig.getFiles()));
                dto.setZipFileUrl(publishedConfig.getZipFileUrl());
                dto.setDevAgentConversationId(publishedConfig.getDevAgentConversationId());
                return dto;
            }
        } catch (Exception e) {
            log.debug("parse skill published config as v2 failed", e);
        }
        SkillConfigDto dto = JSON.parseObject(config, SkillConfigDto.class);
        if (dto != null) {
            dto.setFiles(normalizeSkillFiles(dto.getFiles()));
            if (dto.getExt() == null && ext != null) {
                dto.setExt(parseSkillExtDto(ext));
            }
        }
        return dto;
    }

    private SkillExtDto parseSkillExtDto(Object ext) {
        if (ext == null) {
            return null;
        }
        try {
            if (ext instanceof SkillExtDto skillExtDto) {
                return skillExtDto;
            }
            if (ext instanceof String extStr) {
                if (StringUtils.isBlank(extStr)) {
                    return null;
                }
                // extStr 可能是 JSON 对象字符串，也可能是“被 JSON 编码过的字符串”（外层带引号/转义）。
                try {
                    return JSON.parseObject(extStr, SkillExtDto.class);
                } catch (Exception ignored) {
                    // 继续尝试解码一次
                }
                Object parsed = JSON.parse(extStr);
                if (parsed instanceof String innerStr) {
                    return JSON.parseObject(innerStr, SkillExtDto.class);
                }
                return JSON.parseObject(JSON.toJSONString(parsed), SkillExtDto.class);
            }
            // ext 可能是 Map / LinkedHashMap 等结构
            return JSON.parseObject(JSON.toJSONString(ext), SkillExtDto.class);
        } catch (Exception e) {
            log.debug("parse skill ext failed, extType={}, extValue={}", ext.getClass().getName(), ext, e);
            return null;
        }
    }

    private List<SkillFileDto> parseFilesUpdate(List<SkillFileDto> existFiles, List<SkillFileDto> filesUpdate, Long skillId) {
        if (filesUpdate == null || filesUpdate.isEmpty()) {
            return null;
        }

        // 创建一个映射表，跟踪当前文件
        Map<String, SkillFileDto> currentFiles = new HashMap<>();
        if (existFiles != null) {
            for (SkillFileDto file : existFiles) {
                currentFiles.put(file.getName(), file);
            }
        }

        // 处理更新操作
        for (SkillFileDto fileUpdate : filesUpdate) {
            String operation = fileUpdate.getOperation();

            if ("create".equals(operation) || "modify".equals(operation)) {
                // 创建或修改文件
                SkillFileDto uploaded;
                if (Boolean.TRUE.equals(fileUpdate.getIsDir())) {
                    uploaded = uploadOrBuildDirectory(fileUpdate, TARGET_TYPE_SKILL_DEV, skillId);
                } else if (StringUtils.isNotBlank(fileUpdate.getFileProxyUrl()) && StringUtils.isBlank(fileUpdate.getContents())) {
                    uploaded = new SkillFileDto();
                    uploaded.setName(fileUpdate.getName());
                    uploaded.setIsDir(false);
                    uploaded.setFileProxyUrl(fileUpdate.getFileProxyUrl());
                } else {
                    uploaded = uploadOrBuildDirectory(fileUpdate, TARGET_TYPE_SKILL_DEV, skillId);
                }
                if (uploaded == null) {
                    continue;
                }
                SkillFileDto old = currentFiles.get(fileUpdate.getName());
                if (old != null && !Boolean.TRUE.equals(old.getIsDir())) {
                    deleteSkillFile(old);
                }
                currentFiles.put(uploaded.getName(), uploaded);
            } else if ("delete".equals(operation)) {
                // 删除文件或目录
                if (Boolean.TRUE.equals(fileUpdate.getIsDir())) {
                    // 删除目录：删除该目录本身及其下所有文件
                    // 只保护根目录下的 SKILL.md，目录内的 SKILL.md 允许被删除
                    String dirName = fileUpdate.getName();
                    String dirPrefix = dirName.endsWith("/") ? dirName : dirName + "/";

                    List<String> keysToRemove = new ArrayList<>();
                    for (String key : currentFiles.keySet()) {
                        if (key.equals(dirName) || key.equals(dirPrefix) || key.startsWith(dirPrefix)) {
                            keysToRemove.add(key);
                        }
                    }
                    for (String key : keysToRemove) {
                        deleteSkillFile(currentFiles.get(key));
                        currentFiles.remove(key);
                    }
                } else {
                    // 删除单个文件
                    // 不允许删除SKILL.md
                    if ("SKILL.md".equalsIgnoreCase(fileUpdate.getName())) {
                        throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillMdDeleteForbidden);
                    }
                    deleteSkillFile(currentFiles.get(fileUpdate.getName()));
                    currentFiles.remove(fileUpdate.getName());
                }
            } else if ("rename".equals(operation)) {
                // 重命名文件或目录
                if (fileUpdate.getRenameFrom() != null) {
                    String renameFrom = fileUpdate.getRenameFrom();
                    String renameTo = fileUpdate.getName();

                    if (Boolean.TRUE.equals(fileUpdate.getIsDir())) {
                        // 目录重命名：更新该目录本身及其下所有文件的路径
                        String dirPrefix = renameFrom.endsWith("/") ? renameFrom : renameFrom + "/";
                        String newDirPrefix = renameTo.endsWith("/") ? renameTo : renameTo + "/";
                        List<String> keysToRename = new ArrayList<>();
                        for (String key : currentFiles.keySet()) {
                            // 匹配目录本身（可能没有/结尾）或目录下的文件
                            if (key.equals(renameFrom) || key.startsWith(dirPrefix)) {
                                keysToRename.add(key);
                            }
                        }
                        if (keysToRename.isEmpty()) {
                            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillDirRenameFailed);
                        }
                        keysToRename.sort(Comparator.comparingInt(String::length));
                        for (String oldKey : keysToRename) {
                            SkillFileDto original = currentFiles.remove(oldKey);
                            if (original == null) {
                                continue;
                            }
                            String newKey = oldKey.equals(renameFrom) ? renameTo : newDirPrefix + oldKey.substring(dirPrefix.length());
                            SkillFileDto renamed = renameSkillFile(original, newKey, TARGET_TYPE_SKILL_DEV, skillId);
                            currentFiles.put(newKey, renamed);
                        }
                    } else {
                        // 单个文件重命名
                        SkillFileDto file = currentFiles.remove(renameFrom);
                        if (file != null) {
                            SkillFileDto renamed = renameSkillFile(file, renameTo, TARGET_TYPE_SKILL_DEV, skillId);
                            currentFiles.put(renameTo, renamed);
                        } else {
                            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillFileRenameFailed);
                        }
                    }
                } else {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillRenameSourceMissing);
                }
            } else {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillUnknownFileOperation);
            }
        }

        return compactDirectoryEntries(new ArrayList<>(currentFiles.values()));
    }

    private String readZipEntryContent(ZipInputStream zipInputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        long total = 0L;
        while ((len = zipInputStream.read(buffer)) != -1) {
            total += len;
            if (total > MAX_SINGLE_FILE_SIZE) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillSingleFileSizeExceeded);
            }
            outputStream.write(buffer, 0, len);
        }
        return outputStream.toString(StandardCharsets.UTF_8);
    }

    /**
     * 读取 ZIP 条目为字节数组（用于二进制文件）
     */
    private byte[] readZipEntryBytes(ZipInputStream zipInputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = zipInputStream.read(buffer)) != -1) {
            // 这里不再单独累加判断，由外层的 MAX_SINGLE_FILE_SIZE 控制整体大小
            outputStream.write(buffer, 0, len);
        }
        return outputStream.toByteArray();
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

    //找到所有路径的共同顶层目录前缀
    private String findTopLevelPrefix(List<ZipEntryInfo> entryInfos) {
        if (entryInfos == null || entryInfos.isEmpty()) {
            return null;
        }

        // 收集所有非空的路径，过滤掉系统文件和隐藏文件
        List<String> paths = new ArrayList<>();
        for (ZipEntryInfo info : entryInfos) {
            String name = info.getName();
            if (name != null && !name.isBlank() && !shouldSkipFile(name)) {
                paths.add(name);
            }
        }

        if (paths.isEmpty()) {
            return null;
        }

        // 找到第一个路径的第一个目录段
        String firstPath = paths.get(0);
        int firstSlashIndex = firstPath.indexOf('/');
        if (firstSlashIndex <= 0) {
            // 没有顶层目录，所有文件都在根目录
            return null;
        }

        String candidatePrefix = firstPath.substring(0, firstSlashIndex + 1);

        // 检查所有路径是否都以这个前缀开头
        boolean allHavePrefix = true;
        for (String path : paths) {
            if (!path.startsWith(candidatePrefix)) {
                allHavePrefix = false;
                break;
            }
        }

        return allHavePrefix ? candidatePrefix : null;
    }

    /**
     * 判断是否应该跳过该文件
     * 仅过滤：__MACOSX、.DS_Store 等系统级文件（macOS/Windows/Linux）
     * .skill.json 需保留用于解析导入元数据（不加入技能文件列表）
     * 其他隐藏文件（如 .env、.gitignore）不过滤
     */
    private boolean shouldSkipFile(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }

        String normalizedPath = path.replace('\\', '/');

        // .skill.json 需保留用于解析导入元数据（icon 等），不作为技能文件存储
        if (normalizedPath.toLowerCase().endsWith(".skill.json")) {
            return false;
        }

        // macOS: __MACOSX/ 目录
        if (normalizedPath.startsWith("__MACOSX/") || normalizedPath.startsWith("__MACOSX\\")) {
            return true;
        }

        // 检查路径中是否存在系统级隐藏文件/目录
        String[] parts = normalizedPath.split("/");
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            // macOS: .DS_Store, ._* (AppleDouble), .AppleDouble, .Trashes
            if (".DS_Store".equals(part) || part.startsWith("._") || ".AppleDouble".equals(part) || ".Trashes".equals(part)) {
                return true;
            }
            // Windows: Thumbs.db, desktop.ini
            if ("Thumbs.db".equalsIgnoreCase(part) || "desktop.ini".equalsIgnoreCase(part)) {
                return true;
            }
            // Linux: .directory (KDE)
            if (".directory".equals(part)) {
                return true;
            }
        }

        return false;
    }

    //ZIP 条目信息
    private static class ZipEntryInfo {
        private final String name;
        private final boolean isDirectory;
        private final String content;

        public ZipEntryInfo(String name, boolean isDirectory, String content) {
            this.name = name;
            this.isDirectory = isDirectory;
            this.content = content;
        }

        public String getName() {
            return name;
        }

        public boolean isDirectory() {
            return isDirectory;
        }

        public String getContent() {
            return content;
        }
    }

    /**
     * 构建单个技能的导出 zip
     */
    private byte[] buildSkillZipBytes(SkillConfigDto skillConfigDto, String folderName, boolean includeMeta) {
        String baseDir = folderName.endsWith("/") ? folderName : folderName + "/";
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(); ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {

            // 写入文件内容
            if (!CollectionUtils.isEmpty(skillConfigDto.getFiles())) {
                Set<String> addedEntries = new HashSet<>();
                // 先收集所有文件路径，用于判断目录是否已被文件隐式包含
                Set<String> filePaths = skillConfigDto.getFiles().stream()
                        .filter(f -> f != null && f.getName() != null && !f.getName().isBlank() && !Boolean.TRUE.equals(f.getIsDir()))
                        .map(f -> {
                            String fileName = f.getName();
                            // 规范化文件名：去除前导斜杠，避免双斜杠
                            if (fileName.startsWith("/")) {
                                fileName = fileName.substring(1);
                            }
                            return baseDir + fileName;
                        })
                        .collect(Collectors.toSet());

                for (SkillFileDto fileDto : skillConfigDto.getFiles()) {
                    if (fileDto == null || fileDto.getName() == null || fileDto.getName().isBlank()) {
                        continue;
                    }
                    String fileName = fileDto.getName();
                    // 规范化文件名：去除前导斜杠，避免双斜杠
                    if (fileName.startsWith("/")) {
                        fileName = fileName.substring(1);
                    }
                    String entryName = baseDir + fileName;
                    // 如果是目录，确保路径以/结尾
                    if (Boolean.TRUE.equals(fileDto.getIsDir())) {
                        if (!entryName.endsWith("/")) {
                            entryName = entryName + "/";
                        }
                        // 跳过已添加的目录
                        if (addedEntries.contains(entryName)) {
                            continue;
                        }
                        // 如果有文件路径以该目录为前缀，则跳过该目录（文件会隐式创建目录）
                        String dirPrefix = entryName;
                        boolean hasFileUnderDir = filePaths.stream().anyMatch(f -> f.startsWith(dirPrefix));
                        if (hasFileUnderDir) {
                            continue;
                        }
                        addedEntries.add(entryName);
                        ZipEntry entry = new ZipEntry(entryName);
                        zos.putNextEntry(entry);
                        zos.closeEntry();
                    } else {
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
                        } else if (StringUtils.isNotBlank(fileDto.getFileProxyUrl())) {
                            byte[] bytes = downloadFileBytes(fileDto.getFileProxyUrl());
                            zos.write(bytes);
                        }
                        zos.closeEntry();
                    }
                }
            }

            // 写入元数据（根据参数控制）
            if (includeMeta) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("name", skillConfigDto.getName());
                meta.put("enName", skillConfigDto.getEnName());
                meta.put("description", skillConfigDto.getDescription());
                meta.put("icon", skillConfigDto.getIcon());

                ZipEntry metaEntry = new ZipEntry(baseDir + ".skill.json");
                zos.putNextEntry(metaEntry);
                String metaJson = JSON.toJSONString(meta);
                zos.write(metaJson.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            zos.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            log.error("[exportSkill] 打包技能 zip 失败, skillId={}", skillConfigDto.getId(), e);
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillExportFailed);
        }
    }

    private void addConfigHistory(Long skillId, ConfigHistory.Type type, String description) {
        SkillConfigDto skillConfigDto = queryById(skillId, false);
        String config = JsonSerializeUtil.toJSONStringGeneric(skillConfigDto);

        ConfigHistory configHistory = ConfigHistory.builder()
                .config(config)
                .targetId(skillId)
                .description(description)
                .targetType(Published.TargetType.Skill)
                .opUserId(RequestContext.get().getUserId())
                .type(type)
                .build();
        configHistoryDomainService.addConfigHistory(configHistory);
    }

}
