package com.xspaceagi.agent.core.application.service;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.application.AgentWorkspaceApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.SkillConfig;
import com.xspaceagi.agent.core.domain.service.SkillDomainService;
import com.xspaceagi.agent.core.infra.rpc.SkillFileClient;
import com.xspaceagi.agent.core.spec.utils.FileTypeUtils;
import com.xspaceagi.agent.core.spec.utils.MarkdownExtractUtil;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.file.InMemoryMultipartFile;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
public class AgentWorkspaceApplicationServiceImpl implements AgentWorkspaceApplicationService {

    @Resource
    private SkillFileClient skillFileClient;
    @Resource
    private SkillDomainService skillDomainService;
    @Resource
    private PublishApplicationService publishApplicationService;

    @Override
    public void createWorkspace(CreateWorkspaceDto createWorkspaceDto) {
        log.info("[createWorkspace] userId={} cId={} skillIds={}, subagents.size={}  创建工作空间开始",
                createWorkspaceDto.getUserId(), createWorkspaceDto.getCId(), createWorkspaceDto.getSkillIds(), CollectionUtils.size(createWorkspaceDto.getSubagents()));
        Long userId = createWorkspaceDto.getUserId();
        Long cId = createWorkspaceDto.getCId();
        List<Long> skillIds = createWorkspaceDto.getSkillIds();
        List<SubagentDto> subagents = createWorkspaceDto.getSubagents();

        if (userId == null || cId == null) {
            throw new BizException("必传参数为空");
        }

        List<SkillConfigDto> toPushSkills = new ArrayList<>();
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
                    SkillConfigDto dto = JSON.parseObject(config, SkillConfigDto.class);

                    if (CollectionUtils.isEmpty(dto.getFiles())) {
                        log.warn("[createWorkspace] userId={} cId={} skillId={} 技能无文件，跳过", userId, cId, skillConfig.getId());
                        continue;
                    }

                    List<SkillFileDto> keyFiles = dto.getFiles().stream().filter(file -> "SKILL.MD".equalsIgnoreCase(file.getName()) && !Boolean.TRUE.equals(file.getIsDir())).toList();
                    if (CollectionUtils.isNotEmpty(keyFiles)) {
                        for (SkillFileDto file : keyFiles) {
                            String name = MarkdownExtractUtil.extractFieldValue(file.getContents(), "name");
                            if (StringUtils.isNotBlank(name)) {
                                dto.setEnName(name);
                                break;
                            }
                        }
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

        Map<String, Object> result = skillFileClient.createWorkSpace(userId, cId, zipFile);

        if (result == null) {
            throw new BizException("创建工作空间失败");
        }

        Object successObj = result.get("success");
        if (successObj instanceof Boolean && !(Boolean) successObj) {
            String message = result.getOrDefault("message", "创建工作空间失败").toString();
            log.error("[createWorkspace] userId={} cId={} 创建工作空间失败, message={}", userId, cId, message);
            throw new BizException(message);
        }
        log.info("[createWorkspace] userId={} cId={} 创建工作空间成功", userId, cId);
    }

    @Override
    public void addSkillsToWorkspace(AddSkillsToWorkspaceDto addSkillsToWorkspaceDto) {
        Long userId = addSkillsToWorkspaceDto.getUserId();
        Long cId = addSkillsToWorkspaceDto.getCId();
        MultipartFile zipFile = buildZip(addSkillsToWorkspaceDto.getSkillConfigs(), Collections.emptyList());
        if (zipFile == null) {
            throw new BizException("打包技能 zip 失败");
        }

        Map<String, Object> result = skillFileClient.pushSkillsToWorkspace(userId, cId, zipFile);

        if (result == null) {
            log.error("[addSkills] userId={} cId={} 推送技能文件失败，响应为空", userId, cId);
            throw new BizException("推送技能文件失败");
        }

        Object successObj = result.get("success");
        if (successObj instanceof Boolean && !(Boolean) successObj) {
            String message = result.getOrDefault("message", "推送技能文件失败").toString();
            log.error("[addSkills] userId={} cId={} 推送技能文件失败, message={}", userId, cId, message);
            throw new BizException(message);
        }

        log.info("[addSkills] userId={} cId={} 动态增加技能完成", userId, cId);
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

    //
    // ------------------ 以下是private方法 -----------------------
    //

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
            throw new BizException("打包 skill/agent 失败");
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
            log.warn("文件 {} 的内容不是有效的 base64 编码，按文本处理", fileName);
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

}
