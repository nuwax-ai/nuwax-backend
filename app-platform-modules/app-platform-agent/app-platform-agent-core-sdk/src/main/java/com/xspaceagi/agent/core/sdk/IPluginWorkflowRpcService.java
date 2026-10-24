package com.xspaceagi.agent.core.sdk;

import java.util.List;
import java.util.Map;

/**
 * 插件/工作流绑定执行门面（连接器工具绑定复用）。
 *
 * <p>连接器模块（app-platform-connector）通过该 SPI 在进程内调用 agent 的插件/工作流执行能力，
 * 遵循既有 SPI 模式（bill/mcp/memory 等模块依赖 agent-core-sdk，agent 侧 @Service 实现）。
 * 接口只依赖简单类型，不暴露 agent 内部 DTO。
 */
public interface IPluginWorkflowRpcService {

    /**
     * 执行绑定的插件或工作流（连接器工具绑定执行）。
     *
     * @param bindType PLUGIN / WORKFLOW
     * @param refId    插件/工作流 id
     * @param params   工具入参（透传）
     * @param userId   调用用户 id（用于发布/空间权限校验与上下文填充）
     * @param tenantId 调用租户 id
     * @return 执行结果（插件 result / 工作流 data）
     */
    Object execute(String bindType, Long refId, Map<String, Object> params, Long userId, Long tenantId);

    /**
     * 可绑定列表（仅已发布、当前空间/用户可见），供连接器工具编辑器选择器使用。
     *
     * @param bindType PLUGIN / WORKFLOW
     * @param spaceId  目标空间 id；为空时取 userId 的主空间
     * @param userId   调用用户 id
     * @return [{id,name,description,icon,inputArgs,outputArgs}]
     */
    List<Map<String, Object>> listBindable(String bindType, Long spaceId, Long userId);
}
