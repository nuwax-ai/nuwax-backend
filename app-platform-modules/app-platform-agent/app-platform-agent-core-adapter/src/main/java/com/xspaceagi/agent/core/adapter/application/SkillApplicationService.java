package com.xspaceagi.agent.core.adapter.application;

import com.xspaceagi.agent.core.adapter.dto.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

public interface SkillApplicationService {

    /**
     * 添加技能
     */
    Long add(SkillConfigDto skillConfigDto);

    /**
     * 更新技能
     */
    void update(SkillConfigDto skillConfigDto, boolean isReplaceFiles);

    /**
     * 删除技能
     */
    void delete(Long skillId);

    /**
     * 根据ID查询技能
     */
    SkillConfigDto queryById(Long skillId, boolean loadFiles);

    /**
     * 根据ID查询技能
     */
    SkillConfigDto queryById(Long skillId);

    /**
     * 查询已发布的技能配置
     */
    SkillConfigDto queryPublishedSkillConfig(Long skillId, Long spaceId, boolean loadFiles);

    /**
     * 查询用户相关的已发布的技能，过滤掉没有权限的技能
     */
    List<SkillConfigDto> queryUserRelatedPublishedSkillConfigs(Long userId, List<Long> skillIds);

    /**
     * 查询技能列表
     */
    List<SkillConfigDto> queryList(SkillQueryDto queryDto);

    /**
     * 导出技能
     */
    SkillExportResultDto exportSkill(Long skillId);

    /**
     * 导出技能
     */
    SkillExportResultDto exportSkill(SkillConfigDto skillConfigDto);

    /**
     * 导入技能
     */
    Long importSkill(MultipartFile file, SkillConfigDto existSkill, Long targetSpaceId);

    /**
     * 复制技能
     */
    Long copySkill(SkillConfigDto skillConfigDto, Long targetSpaceId);

    /**
     * 查询技能模板
     */
    SkillConfigDto getSkillTemplate(InputStream inputStream);

    /**
     * 校验空间技能权限
     */
    void checkSpaceSkillPermission(Long spaceId, Long skillId);

    /**
     * 处理上传的文件，将文件内容转换为 SkillFileDto
     * 如果是二进制文件则转换为 base64，文本文件则直接读取
     */
    SkillFileDto processUploadFile(MultipartFile file, String filePath);

    /**
     * 记录最近使用的技能
     */
    void saveRecentlyUsedSkills(List<Long> skillIds);

    /**
     * 查询最近使用的技能
     */
    List<PublishedDto> queryRecentlyUsedSkills(String kw, Integer size);
}
