package com.xspaceagi.agent.core.adapter.util;

import com.xspaceagi.agent.core.adapter.dto.SkillConfigDto;
import com.xspaceagi.agent.core.adapter.dto.SkillFileDto;
import com.xspaceagi.agent.core.spec.utils.MarkdownExtractUtil;
import com.xspaceagi.file.sdk.IFileAccessService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class SkillNameUtil {

    private SkillNameUtil() {
    }

    /**
     * 从 SKILL.md frontmatter 的 name 回填 enName（目录名/技能名）；
     * 提取不到时回退 enName/name 原值
     */
    public static void backfillName(SkillConfigDto skillConfigDto, IFileAccessService iFileAccessService) {
        doBackfill(skillConfigDto, iFileAccessService, false);
    }

    /**
     * 回填 name/description：取 SKILL.md frontmatter ，
     * 提取不到保留 dto 原值
     */
    public static void backfillNameAndDescription(SkillConfigDto skillConfigDto, IFileAccessService iFileAccessService) {
        doBackfill(skillConfigDto, iFileAccessService, true);
    }

    private static void doBackfill(SkillConfigDto skillConfigDto, IFileAccessService iFileAccessService, boolean withDescription) {
        if (skillConfigDto == null) {
            return;
        }
        String fallbackName = StringUtils.defaultIfBlank(skillConfigDto.getEnName(), skillConfigDto.getName());
        var files = skillConfigDto.getFiles();
        if (CollectionUtils.isEmpty(files)) {
            if (StringUtils.isNotBlank(fallbackName)) {
                skillConfigDto.setEnName(fallbackName);
            }
            return;
        }
        String nameFromMd = null;
        String descriptionFromMd = null;
        for (SkillFileDto file : files) {
            if (file == null || Boolean.TRUE.equals(file.getIsDir()) || !"SKILL.MD".equalsIgnoreCase(file.getName())) {
                continue;
            }
            String skillMdContent = resolveSkillFileContent(file, iFileAccessService);
            if (StringUtils.isBlank(skillMdContent)) {
                continue;
            }
            if (StringUtils.isBlank(nameFromMd)) {
                nameFromMd = MarkdownExtractUtil.extractFieldValue(skillMdContent, "name");
            }
            if (withDescription && StringUtils.isBlank(descriptionFromMd)) {
                descriptionFromMd = MarkdownExtractUtil.extractFieldValue(skillMdContent, "description");
            }
            boolean nameReady = StringUtils.isNotBlank(nameFromMd);
            boolean descriptionReady = !withDescription || isValidDescription(descriptionFromMd);
            if (nameReady && descriptionReady) {
                break;
            }
        }
        if (StringUtils.isNotBlank(nameFromMd)) {
            skillConfigDto.setEnName(nameFromMd);
        } else if (StringUtils.isNotBlank(fallbackName)) {
            skillConfigDto.setEnName(fallbackName);
        }
        if (isValidDescription(descriptionFromMd)) {
            skillConfigDto.setDescription(descriptionFromMd);
        }
    }

    /**
     * description 值有效性校验：折叠/块样式（>-、| 等）的指示符不是真实值，按未提取到处理，
     * 保留发布配置的 description
     */
    private static boolean isValidDescription(String description) {
        if (StringUtils.isBlank(description)) {
            return false;
        }
        return !description.matches("^[>|][0-9+\\-]?");
    }

    private static String resolveSkillFileContent(SkillFileDto skillFileDto, IFileAccessService iFileAccessService) {
        if (skillFileDto == null) {
            return null;
        }
        if (StringUtils.isNotBlank(skillFileDto.getFileProxyUrl()) && iFileAccessService != null) {
            String content = downloadSkillFileContent(skillFileDto.getFileProxyUrl(), iFileAccessService);
            if (StringUtils.isNotBlank(content)) {
                return content;
            }
        }
        return skillFileDto.getContents();
    }

    private static String downloadSkillFileContent(String fileProxyUrl, IFileAccessService iFileAccessService) {
        try {
            String fileUrlWithAk = iFileAccessService.getFileUrlWithAk(fileProxyUrl, true);
            URL fileUrl = new URL(fileUrlWithAk);
            try (InputStream in = fileUrl.openStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
