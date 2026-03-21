package com.xspaceagi.agent.core.application.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.application.SkillApplicationService;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.repository.CopyIndexRecordRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.ConfigHistory;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.SkillConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.UserTargetRelation;
import com.xspaceagi.agent.core.domain.service.ConfigHistoryDomainService;
import com.xspaceagi.agent.core.domain.service.PublishDomainService;
import com.xspaceagi.agent.core.domain.service.SkillDomainService;
import com.xspaceagi.agent.core.domain.service.UserTargetRelationDomainService;
import com.xspaceagi.agent.core.spec.utils.FileTypeUtils;
import com.xspaceagi.agent.core.spec.utils.MarkdownExtractUtil;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.YnEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
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

    // 单个文件最大大小100M
    private final static long MAX_SINGLE_FILE_SIZE = 100 * 1024 * 1024L;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long add(SkillConfigDto skillConfigDto) {
        SkillConfig skillConfig = new SkillConfig();
        BeanUtils.copyProperties(skillConfigDto, skillConfig);
        if (StringUtils.isBlank(skillConfig.getName())) {
            throw new BizException("技能名称不能为空");
        }

        if (skillConfig.getFiles() != null) {
            skillConfig.getFiles().forEach(file -> {
                file.setOperation(null);
            });
        }

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
        addConfigHistory(skillId, ConfigHistory.Type.Add, "新增技能");
        return skillId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(SkillConfigDto skillConfigDto, boolean isReplaceFiles) {
        // 这里查一遍，是为了取之前的 files，跟本次传入的files做比对
        SkillConfigDto exist = queryById(skillConfigDto.getId(), true);
        if (exist == null) {
            throw new BizException("技能不存在");
        }

        SkillConfig skillConfig = new SkillConfig();
        BeanUtils.copyProperties(skillConfigDto, skillConfig);

        if (isReplaceFiles) { //替换
            if (skillConfigDto.getFiles() != null && !skillConfigDto.getFiles().isEmpty()) {
                skillConfig.setFiles(skillConfigDto.getFiles());
            } else {
                skillConfig.setFiles(new ArrayList<>());
            }
        } else { //更新
            if (skillConfigDto.getFiles() != null && !skillConfigDto.getFiles().isEmpty()) {
                List<SkillFileDto> newFiles = parseFilesUpdate(exist.getFiles(), skillConfigDto.getFiles());
                skillConfig.setFiles(newFiles);
            } else {
                // 没有传文件必须显式设置成 null，不然可能清空files
                skillConfig.setFiles(null);
            }
        }

        // 文件已确定,不需要把operation记到库中
        if (skillConfig.getFiles() != null) {
            skillConfig.getFiles().forEach(file -> {
                file.setOperation(null);
            });
        }
        skillDomainService.update(skillConfig);
        addConfigHistory(skillConfig.getId(), ConfigHistory.Type.Edit, "更新技能");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long skillId) {
        if (skillId == null) {
            throw new IllegalArgumentException("技能ID不能为空");
        }
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

        PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Skill, skillId, false);
        if (publishedDto != null) {
            skillConfigDto.setPublishDate(publishedDto.getModified());
            skillConfigDto.setScope(publishedDto.getScope());
            skillConfigDto.setCategory(publishedDto.getCategory());
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
        SkillConfigDto skillConfigDto = JSON.parseObject(publishedDto.getConfig(), SkillConfigDto.class);
        if (skillConfigDto == null) {
            skillConfigDto = new SkillConfigDto();
            skillConfigDto.setId(skillId);
            skillConfigDto.setName(publishedDto.getName());
            skillConfigDto.setDescription(publishedDto.getDescription());
            skillConfigDto.setIcon(publishedDto.getIcon());
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
            SkillConfigDto skillConfigDto = JSON.parseObject(publishedDto.getConfig(), SkillConfigDto.class);
            if (skillConfigDto == null || CollectionUtils.isEmpty(skillConfigDto.getFiles())) {
                return;
            }
            if (publishedDto.getScope() != Published.PublishScope.Tenant && publishedDto.getPublishedSpaceIds().stream().noneMatch(spaceIds::contains)) {
                log.info("用户 {} 无权限技能：{}", userId, skillId);
                return;
            }
            if (StringUtils.isBlank(skillConfigDto.getEnName())) {
                List<SkillFileDto> keyFiles = skillConfigDto.getFiles().stream().filter(file -> "SKILL.MD".equalsIgnoreCase(file.getName()) && !Boolean.TRUE.equals(file.getIsDir())).toList();
                if (CollectionUtils.isNotEmpty(keyFiles)) {
                    for (SkillFileDto file : keyFiles) {
                        String name = MarkdownExtractUtil.extractFieldValue(file.getContents(), "name");
                        if (StringUtils.isNotBlank(name)) {
                            skillConfigDto.setEnName(name);
                            break;
                        }
                    }
                } else {
                    return;
                }
            }
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

        List<SkillConfig> skillConfigs = skillDomainService.list(queryWrapper);

        if (CollectionUtils.isEmpty(skillConfigs)) {
            return List.of();
        }

        return skillConfigs.stream().map(skillConfig -> {
            SkillConfigDto skillConfigDto = new SkillConfigDto();
            BeanUtils.copyProperties(skillConfig, skillConfigDto);
            // 列表查询不返回 files，避免大字段影响性能
            skillConfigDto.setFiles(null);
            return skillConfigDto;
        }).collect(Collectors.toList());
    }

    @Override
    public SkillExportResultDto exportSkill(Long skillId) {
        SkillConfigDto skillConfigDto = queryById(skillId, true);
        return exportSkill(skillConfigDto);
    }

    @Override
    public SkillExportResultDto exportSkill(SkillConfigDto skillConfigDto) {
        if (skillConfigDto == null) {
            throw new BizException("技能不能为空");
        }
        if (StringUtils.isBlank(skillConfigDto.getName())) {
            throw new BizException("技能名称不能为空");
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
    public Long importSkill(MultipartFile file, SkillConfigDto existSkill, Long targetSpaceId) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null) {
            throw new BizException("文件名不能为空");
        }
        String fileNameLower = fileName.toLowerCase();
        boolean isZipLike = fileNameLower.endsWith(".zip") || fileNameLower.endsWith(".skill");
        boolean isSingleSkillMd = "skill.md".equals(fileNameLower);

        if (!isZipLike && !isSingleSkillMd) {
            throw new BizException("文件必须是zip/.skill格式，或者为SKILL.md文件");
        }

        if (existSkill == null && targetSpaceId == null) {
            throw new BizException("请选择目标空间");
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
                        String errorMsg = String.format("文件: 第 %d 个条目(无法获取文件名), 错误: %s",
                                entryIndex, e.getMessage());
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
                            log.warn("关闭 ZIP 条目失败: {}", entryName, e);
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
                                String errorMsg = String.format("文件: %s, 错误: 不支持的压缩方法 %d (仅支持 STORED(0) 和 DEFLATED(8))",
                                        entryName, method);
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
                                String errorMsg = String.format("文件: %s, 错误: 文件大小 %.2f M 超过限制 %.2f M",
                                        entryName, entrySizeMB, maxSizeMB);
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
                            String errorMsg = String.format("文件: %s, 错误: %s", entryName, e.getMessage());
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
                            String errorMsg = String.format("文件: %s, 错误: %s", entryName, e.getMessage());
                            errorFiles.add(errorMsg);
                            try {
                                zipInputStream.closeEntry();
                            } catch (Exception ex) {
                                // 忽略关闭异常
                            }
                            continue;
                        } catch (Exception e) {
                            // 其他读取异常，记录错误信息
                            String errorMsg = String.format("文件: %s, 错误: %s", entryName, e.getMessage());
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
                        log.warn("关闭 ZIP 条目失败: {}", entryName, e);
                    }
                }

                // 如果有错误文件，统一抛出异常
                if (!errorFiles.isEmpty()) {
                    StringBuilder errorMessage = new StringBuilder("ZIP 文件中存在以下问题文件：\n");
                    for (int i = 0; i < errorFiles.size(); i++) {
                        errorMessage.append(String.format("%d. %s\n", i + 1, errorFiles.get(i)));
                    }
                    errorMessage.append("\n请检查并修复这些问题文件后重新上传。");
                    throw new BizException(errorMessage.toString());
                }

            } catch (BizException e) {
                log.error("解析技能导入文件失败", e);
                throw e;
            } catch (Exception e) {
                log.error("解析技能导入文件失败", e);
                // 如果是 ZipException，提供更具体的错误信息
                if (e instanceof ZipException) {
                    throw new BizException("ZIP 文件格式错误或不兼容，请使用标准的 ZIP 压缩格式重新打包文件。错误详情: " + e.getMessage());
                }
                throw new BizException("文件格式错误，请上传正确的技能文件。错误详情: " + e.getMessage());
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
                    throw new BizException("文件大小不能超过 100M");
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
                throw new BizException("读取 SKILL.md 文件失败");
            }
        }

        // 校验 SKILL.md 是否存在
        if (!hasSkillMd) {
            throw new BizException("技能包中缺少必需的 SKILL.md 文件");
        }

        // 从 SKILL.md 中解析 name 和 description
        String nameInMd = MarkdownExtractUtil.extractFieldValue(skillMdContent, "name");
        String descriptionInMd = MarkdownExtractUtil.extractFieldValue(skillMdContent, "description");

        if (StringUtils.isAnyBlank(nameInMd, descriptionInMd)) {
            throw new BizException("SKILL.md 文件中必须包含 name 和 description 信息");
        }

        skillConfigDto.setFiles(files);
        skillConfigDto.setName(nameInMd);
        skillConfigDto.setDescription(descriptionInMd);

        if (existSkill != null) {
            skillConfigDto.setId(existSkill.getId());
            skillConfigDto.setSpaceId(existSkill.getSpaceId());
        } else {
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
            log.error("读取技能模板失败", e);
            throw new BizException("读取技能模板失败: " + e.getMessage());
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
            throw new BizException("技能不存在或无权限使用");
        }
    }

    @Override
    public SkillFileDto processUploadFile(MultipartFile file, String filePath) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new BizException("filePath 无效");
        }

        try {
            byte[] fileBytes = file.getBytes();
            if (fileBytes.length > MAX_SINGLE_FILE_SIZE) {
                throw new BizException("文件大小不能超过 100M");
            }

            SkillFileDto fileDto = new SkillFileDto();
            fileDto.setName(filePath);

            // 判断是否为文本文件（白名单方式，更安全）
            String fileContents;
            if (FileTypeUtils.isTextFile(filePath)) {
                // 文本文件直接读取
                fileContents = new String(fileBytes, StandardCharsets.UTF_8);
            } else {
                // 非文本文件（二进制）转换为 base64
                fileContents = Base64.getEncoder().encodeToString(fileBytes);
            }

            fileDto.setContents(fileContents);
            return fileDto;
        } catch (IOException e) {
            log.error("处理上传文件失败", e);
            throw new BizException("处理上传文件失败: " + e.getMessage());
        }
    }

    @Override
    public void saveRecentlyUsedSkills(List<Long> skillIds) {
        Long userId = RequestContext.get() != null ? RequestContext.get().getUserId() : null;
        if (userId == null) {
            throw new BizException("用户未登录");
        }
        List<Long> targetIds = skillIds.stream().filter(Objects::nonNull).toList();
        if (CollectionUtils.isEmpty(targetIds)) {
            throw new BizException("skillIds不能为空");
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
            throw new BizException("用户未登录");
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

    private List<SkillFileDto> parseFilesUpdate(List<SkillFileDto> existFiles, List<SkillFileDto> filesUpdate) {
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
                currentFiles.put(fileUpdate.getName(), fileUpdate);
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
                        currentFiles.remove(key);
                    }
                } else {
                    // 删除单个文件
                    // 不允许删除SKILL.md
                    if ("SKILL.md".equalsIgnoreCase(fileUpdate.getName())) {
                        throw new BizException("SKILL.md 文件不允许删除");
                    }
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
                            throw new BizException("目录重命名失败，源目录不存在或为空");
                        }
                        for (String oldKey : keysToRename) {
                            SkillFileDto file = currentFiles.remove(oldKey);
                            String newKey;
                            if (oldKey.equals(renameFrom)) {
                                // 目录本身
                                newKey = renameTo;
                            } else {
                                // 目录下的文件
                                newKey = newDirPrefix + oldKey.substring(dirPrefix.length());
                            }
                            file.setName(newKey);
                            currentFiles.put(newKey, file);
                        }
                    } else {
                        // 单个文件重命名
                        SkillFileDto file = currentFiles.remove(renameFrom);
                        if (file != null) {
                            file.setName(renameTo);
                            currentFiles.put(renameTo, file);
                        } else {
                            throw new BizException("文件重命名失败，源文件不存在");
                        }
                    }
                } else {
                    throw new BizException("缺少文件重命名的源文件名");
                }
            } else {
                throw new BizException("未知的文件操作类型");
            }
        }

        return new ArrayList<>(currentFiles.values());
    }

    private String readZipEntryContent(ZipInputStream zipInputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        long total = 0L;
        while ((len = zipInputStream.read(buffer)) != -1) {
            total += len;
            if (total > MAX_SINGLE_FILE_SIZE) {
                throw new BizException("单个文件大小不能超过 100M");
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
            log.warn("文件 {} 的内容不是有效的 base64 编码，按文本处理", fileName);
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
            throw new BizException("导出技能失败");
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
