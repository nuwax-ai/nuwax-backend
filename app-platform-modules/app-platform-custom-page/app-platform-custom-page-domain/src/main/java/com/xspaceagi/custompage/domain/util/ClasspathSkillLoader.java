package com.xspaceagi.custompage.domain.util;

import com.xspaceagi.agent.core.adapter.dto.SkillConfigDto;
import com.xspaceagi.agent.core.adapter.dto.SkillFileDto;
import com.xspaceagi.agent.core.spec.utils.FileTypeUtils;
import com.xspaceagi.agent.core.spec.utils.MarkdownExtractUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 classpath 加载固定技能模板（skills/xxx/），构建 SkillConfigDto。
 * name / description 自动从 SKILL.md 的 YAML front matter 中提取，无需调用方传入。
 * 技能模板文件不变，首次加载后以 basePath 为 key 缓存，后续调用直接返回。
 */
@Slf4j
public final class ClasspathSkillLoader {

    private ClasspathSkillLoader() {
    }

    private static final ConcurrentHashMap<String, SkillConfigDto> CACHE = new ConcurrentHashMap<>();

    /**
     * 加载（并缓存）一个 classpath 技能模板。
     * name 和 description 从目录下的 SKILL.md front matter 自动提取。
     *
     * @param basePath classpath 根路径，以 / 结尾（如 "skills/nuwax-pay/"）
     * @return 构建好的 SkillConfigDto（含 name/enName/description/files）
     */
    public static SkillConfigDto load(String basePath) {
        return CACHE.computeIfAbsent(basePath, k -> doLoad(basePath));
    }

    private static SkillConfigDto doLoad(String basePath) {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:" + basePath + "**/*");

            List<SkillFileDto> files = new ArrayList<>();
            String skillName = null;
            String description = null;

            for (Resource resource : resources) {
                if (resource.getFilename() == null) {
                    continue;
                }
                String url = resource.getURL().toString();
                int idx = url.indexOf(basePath);
                if (idx < 0) {
                    continue;
                }
                String relativePath = url.substring(idx + basePath.length());
                if (relativePath.isEmpty() || relativePath.endsWith("/")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream is = resource.getInputStream()) {
                    bytes = is.readAllBytes();
                }
                String contents;
                if (FileTypeUtils.isTextFile(relativePath)) {
                    contents = new String(bytes, StandardCharsets.UTF_8);
                } else {
                    contents = Base64.getEncoder().encodeToString(bytes);
                }

                // 从 SKILL.md 提取 name / description
                if ("SKILL.md".equals(relativePath)) {
                    skillName = MarkdownExtractUtil.extractFieldValue(contents, "name");
                    description = MarkdownExtractUtil.extractFieldValue(contents, "description");
                }

                SkillFileDto fileDto = new SkillFileDto();
                fileDto.setName(relativePath);
                fileDto.setContents(contents);
                files.add(fileDto);
            }

            if (files.isEmpty()) {
                throw new IllegalStateException("No files found under classpath:" + basePath);
            }
            if (skillName == null || skillName.isBlank()) {
                throw new IllegalStateException("SKILL.md front matter missing 'name' under classpath:" + basePath);
            }

            SkillConfigDto skillConfigDto = new SkillConfigDto();
            skillConfigDto.setName(skillName);
            skillConfigDto.setEnName(skillName);
            skillConfigDto.setDescription(description);
            skillConfigDto.setFiles(files);
            return skillConfigDto;
        } catch (IOException e) {
            log.error("[ClasspathSkillLoader] load skill template failed, basePath={}", basePath, e);
            throw new IllegalArgumentException("Load skill template failed, basePath=" + basePath, e);
        }
    }
}
