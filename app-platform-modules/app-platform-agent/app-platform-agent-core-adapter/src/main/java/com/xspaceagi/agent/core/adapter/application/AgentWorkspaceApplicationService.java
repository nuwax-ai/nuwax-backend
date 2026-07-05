package com.xspaceagi.agent.core.adapter.application;

import com.xspaceagi.agent.core.adapter.dto.*;

import java.util.List;

public interface AgentWorkspaceApplicationService {


    /**
     * 创建/重置工作空间（不会重复创建，只重置skill/subagent，不会清除用户文件）
     * 如果传了技能id列表，会同时推送技能文件
     * 如果传了subagent列表，会同时推送subagent文件
     */
    void createWorkspace(CreateWorkspaceDto createWorkspaceDto);

    /**
     * 动态添加技能到工作空间
     * 支持传多个技能 id，动态增加的技能在重置工作空间时不会被清除
     */
    void addSkillsToWorkspace(AddSkillsToWorkspaceDto addSkillsToWorkspaceDto);

    /**
     * 初始化项目模板
     * 根据项目类型和编程语言从 classpath 读取模板 zip，传到工作空间，
     * 根据 git版本开关决定是否执行 git init + commit
     */
    void initProjectTemplate(InitProjectTemplateDto dto);

    /**
     * 安装项目依赖
     * TypeScript 项目执行 pnpm install，Python 项目执行 pip install
     */
    void installProject(InstallProjectDto dto);

    /**
     * 打包 Agent：在沙箱工作空间执行打包脚本，生成各平台压缩包，上传到平台文件存储
     */
    List<AgentPublishVersionDto> packageAgent(PackageAgentDto dto);

    /**
     * 从沙箱工作空间取回技能文件，上传到文件服务，用于技能发布
     */
    List<SkillFileDto> snapshotSkillFilesFromSandbox(Long userId, Long cId, String targetType, Long skillId);

    /**
     * 删除沙箱工作空间
     */
    void deleteWorkspace(Long userId, Long cId);

    /**
     * 从沙箱导出技能为 zip 包
     */
    SkillExportResultDto exportSkillFromSandbox(Long userId, Long cId, String skillName);

    /**
     * 复制源沙箱工作空间到新的工作空间，并初始化 git
     * 采用先打包下载，后上传到新空间的方式，可以实现跨主机的复制
     */
    void copySandboxWorkspace(Long srcUserId, Long srcCId, Long destUserId, Long destCId);

    /**
     * 从指定 url 地址下载 zip 文件并上传到工作空间，初始化项目模板
     */
    void uploadZipToWorkspace(Long userId, Long cId, String zipFileUrl);

}