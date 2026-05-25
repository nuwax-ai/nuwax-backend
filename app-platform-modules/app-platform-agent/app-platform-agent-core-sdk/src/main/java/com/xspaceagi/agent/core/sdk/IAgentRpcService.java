package com.xspaceagi.agent.core.sdk;

import com.xspaceagi.agent.core.sdk.dto.*;
import com.xspaceagi.agent.core.sdk.enums.TargetTypeEnum;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public interface IAgentRpcService {

    ReqResult<List<AgentInfoDto>> queryAgentInfoList(List<Long> agentIds);

    ReqResult<AgentInfoDto> queryPublishedAgentInfo(Long agentId);

    ReqResult<AgentPublishedPermissionDto> queryAgentPublishedPermission(Long agentId);

    /**
     * 查询插件配置信息
     *
     * @param pluginId
     * @param paramJson
     * @return
     */
    ReqResult<String> queryPluginConfig(Long pluginId, String paramJson);

    /**
     * 插件启动或更新
     *
     * @return 返回系统生成的插件ID
     */
    ReqResult<Long> pluginEnableOrUpdate(PluginEnableOrUpdateDto pluginEnableOrUpdateDto);

    /**
     * 禁用插件
     *
     * @return
     */
    ReqResult<Void> disablePlugin(Long pluginId);

    /**
     * 查询模板配置信息（智能体、工作流）
     *
     * @return
     */
    ReqResult<String> queryTemplateConfig(TargetTypeEnum targetType, Long targetId);

    /**
     * 模板启动或更新
     *
     * @return 系统生成的工作流或智能体ID（每次都会返回新的ID，调用方需要更新）
     */
    ReqResult<Long> templateEnableOrUpdate(TemplateEnableOrUpdateDto templateEnableOrUpdateDto);

    /**
     * 禁用模板
     *
     * @return
     */
    ReqResult<Void> disableTemplate(TargetTypeEnum targetType, Long targetId);

    ReqResult<WorkflowInfoDto> getPublishedWorkflowInfo(Long workflowId, Long spaceId);

    ReqResult<SkillInfoDto> getPublishedSkillInfo(Long skillId, Long spaceId);

    List<ArgDto> parseWorkflowPluginBindArgs(String bindConfig);

    ReqResult<PluginInfoDto> getPublishedPluginInfo(Long pluginId, Long spaceId);

    List<ArgDto> parseAgentPluginBindArgs(String bindConfig);

    Mono<Object> executePlugin(PluginExecuteRequestDto pluginExecuteRequest);

    Flux<WorkflowExecuteResultDto> executeWorkflow(WorkflowExecuteRequestDto workflowExecuteRequest);

    Flux<AgentOutputDto> executeAgent(AgentExecuteRequestDto agentExecuteRequestDto);

    ReqResult<String> queryApiSchema(TargetTypeEnum targetType, Long targetId, Long projectId);

    ReqResult<Long> createPageAppAgent(PageAppAgentCreateDto pageAppAgentCreateDto);

    ReqResult<Long> createUserSandboxAgent(Long userId, Long sandboxId, String name);

    ReqResult<Void> updateUserSandboxAgentName(Long agentId, String oldName, String newName);

    ReqResult<Void> updatePageAppAgent(PageAppAgentUpdateDto pageAppAgentUpdateDto);

    ReqResult<Void> deletePageAppAgent(Long pageAppAgentId);

    ReqResult<Void> deleteUserSandboxAgent(Long agentId, Long sandboxId);

    Mono<List<KnowledgeQaDto>> searchKnowledge(KnowledgeSearchRequest knowledgeSearchRequest);
}
