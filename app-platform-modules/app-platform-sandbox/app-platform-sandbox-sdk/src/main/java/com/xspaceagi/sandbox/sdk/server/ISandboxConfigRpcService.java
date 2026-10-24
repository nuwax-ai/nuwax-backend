package com.xspaceagi.sandbox.sdk.server;

import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxGlobalConfigDto;

import java.util.List;

/**
 * 沙盒配置 RPC 服务接口
 */
public interface ISandboxConfigRpcService {

    /**
     * 查询用户配置列表
     *
     * @param userId 用户ID
     * @return 配置列表
     */
    List<SandboxConfigRpcDto> queryUserConfigs(Long userId);

    /**
     * 查询全局配置列表（通用智能体沙箱）
     *
     * @return 配置列表
     */
    List<SandboxConfigRpcDto> queryGlobalConfigs(Long tenantId);

    /**
     * 根据配置键查询用户配置
     *
     * @param configKey 配置键
     * @return 配置信息
     */
    SandboxConfigRpcDto queryUserConfigByKey(String configKey);

    /**
     * 根据配置键查询全局配置
     *
     * @param configKey 配置键
     * @return 配置信息
     */
    SandboxConfigRpcDto queryGlobalConfigByKey(String configKey);

    /**
     * 根据ID查询配置
     *
     * @param id 配置ID
     * @return 配置信息
     */
    SandboxConfigRpcDto queryById(Long id);

    /**
     * 根据ID在当前请求租户上下文内查询配置
     */
    SandboxConfigRpcDto queryByIdInTenant(Long id);


    SandboxGlobalConfigDto getGlobalConfig(Long tenantId);

    Long queryUserSelectedSandboxId(Long userId, Long agentId);

    /** 记录用户为某智能体选择的沙箱 */
    void saveUserSelectedSandboxId(Long userId, Long agentId, Long sandboxId);

    /**
     * 选择应用开发沙盒
     *
     * @param tenantId  租户ID
     * @param userId    用户ID
     * @param spaceId   空间ID
     * @param projectId 项目ID
     * @param sandboxId 沙盒ID，可选，项目如果已经有了就传递
     * @return 沙盒信息
     */
    SandboxConfigRpcDto selectAppDevelopmentSandbox(Long tenantId, Long userId, Long spaceId, Long projectId, Long sandboxId);
}
