package com.xspaceagi.agent.core.adapter.application;


import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import reactor.core.publisher.Flux;

import java.util.List;

public interface WorkflowApplicationService {


    /**
     * 新增工作流
     */
    Long add(WorkflowConfigDto workflowConfig);

    /**
     * 整体保存工作流
     */
    void save(JSONObject workflowConfigJson, WorkflowConfigDto oldWorkflowConfigDto);

    /**
     * 更新工作流
     */
    void update(WorkflowConfigDto workflowConfig);

    /**
     * 删除工作流
     */
    void delete(Long workflowId);

    /**
     * 复制工作流
     *
     * @return
     */
    Long copyWorkflow(Long userId, Long workflowId);

    /**
     * 复制工作流
     *
     * @return
     */
    Long copyWorkflow(Long userId, WorkflowConfigDto workflowConfigDto, Long targetSpaceId);

    void restoreWorkflow(WorkflowConfigDto workflowConfigDto);

    void restoreWorkflow(String historyConfig);

    /**
     * 移动工作流到其他空间
     */
    void transfer(Long workflowId, Long targetSpaceId);

    /**
     * 删除空间下的所有工作流
     *
     * @param spaceId
     */
    void deleteBySpaceId(Long spaceId);

    /**
     * 获取空间下的工作流列表
     */
    List<WorkflowConfigDto> queryListBySpaceId(Long spaceId);

    /**
     * 根据ID获取工作流列表
     *
     * @return
     */
    List<WorkflowConfigDto> queryListByIds(List<Long> workflowIds);

    /**
     * 根据ID获取工作流配置
     *
     * @return
     */
    WorkflowConfigDto queryById(Long workflowId);

    WorkflowConfigDto queryByIdWithoutNodes(Long workflowId);

    //查询已发布的工作流配置
    WorkflowConfigDto queryPublishedWorkflowConfig(Long workflowId, Long spaceId, boolean forExecute);

    WorkflowConfigDto queryPublishedWorkflowConfig(Long workflowId, Long spaceId);

    List<WorkflowConfigDto> queryPublishedWorkflowConfigs(List<Long> workflowIds);

    /**
     * 添加节点
     */
    Long addWorkflowNode(WorkflowNodeAddDto workflowNodeAddDto);

    /**
     * 更新节点配置
     */
    <T> void updateWorkflowNodeConfig(WorkflowNodeUpdateDto<T> workflowNodeDto);

    /**
     * 删除节点
     */
    void deleteWorkflowNode(Long id);

    /**
     * 获取节点列表
     */
    List<WorkflowNodeDto> queryWorkflowNodeList(Long workflowId);

    /**
     * 获取节点列表
     */
    List<WorkflowNodeDto> queryWorkflowNodeListForTestExecute(Long workflowId);

    /**
     * 获取上级节点列表
     */
    PreviousDto queryPreviousNodes(Long nodeId);

    /**
     * 获取节点配置
     *
     * @param id
     * @return
     */
    WorkflowNodeDto queryWorkflowNode(Long id);

    Long copyWorkflowNode(Long id);

    void updateNextIds(Long nodeId, List<Long> nextIds);

    /**
     * 检查用户工作流权限
     *
     * @param spaceId
     * @param workflowId
     */
    void checkSpaceWorkflowPermission(Long spaceId, Long workflowId);

    /**
     * 梳理出工作流节点层级关系，输出给前端配置使用
     *
     * @param workflowNodeDtos
     * @return
     */
    List<WorkflowNodeDto> organizeNodeHierarchicalRelationship(List<WorkflowNodeDto> workflowNodeDtos);

    /**
     * 校验工作流节点配置是否正确，用于发布和试运行之前
     *
     * @param workflowId
     * @return
     */
    List<WorkflowNodeCheckDto> validWorkflow(Long workflowId);

    WorkflowNodeCheckDto validWorkflowNode(WorkflowNodeDto workflowNodeDto);

    void updateLoopInnerNodes(Long loopNodeId, List<JSONObject> innerNodes);


    Flux<WorkflowExecutingDto> executeWorkflow(WorkflowExecuteRequestDto workflowExecuteRequestDto, WorkflowConfigDto workflowConfigDto);

    Long workflowEditVersion(Long workflowId, boolean inc);
}
