package com.xspaceagi.agent.core.adapter.application;

import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.spec.enums.UsageScenarioEnum;
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
     * 解析已发布技能配置（兼容新旧格式）
     */
    SkillConfigDto parsePublishedSkillConfig(String config, Object ext);

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
    Long importSkill(MultipartFile file, SkillConfigDto existSkill, Long targetSpaceId, SkillExtDto ext);

    /**
     * 导入技能（支持文件或URL导入）
     *
     * @param url            技能文件URL（与file二选一）
     * @param file           上传的技能文件（与url二选一）
     * @param targetSkillId  目标技能ID（导入到已有技能），为null时创建新技能
     * @param targetSpaceId  目标空间ID（创建新技能时必填）
     * @param usageScenarios 使用场景列表
     * @return 技能ID
     */
    Long importSkill(String url, MultipartFile file, Long targetSkillId, Long targetSpaceId, List<UsageScenarioEnum> usageScenarios);

    /**
     * 复制技能
     */
    Long copySkill(SkillConfigDto skillConfigDto, Long targetSpaceId);

    /**
     * 查询技能模板
     */
    SkillConfigDto getSkillTemplate(InputStream inputStream);

    /**
     * 获取技能模板（从 classpath 自动读取 skill-template.json）
     */
    SkillConfigDto getSkillTemplate();

    /**
     * 校验空间技能权限
     */
    void checkSpaceSkillPermission(Long spaceId, Long skillId);

    /**
     * 处理上传的文件并上传到文件服务，返回文件索引
     */
    SkillFileDto processUploadFile(MultipartFile file, String filePath, Long skillId);

    /**
     * 记录最近使用的技能
     */
    void saveRecentlyUsedSkills(List<Long> skillIds);

    /**
     * 查询最近使用的技能
     */
    List<PublishedDto> queryRecentlyUsedSkills(String kw, Integer size);
}
