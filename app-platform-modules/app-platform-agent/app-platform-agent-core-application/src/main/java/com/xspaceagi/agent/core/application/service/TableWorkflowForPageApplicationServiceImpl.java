package com.xspaceagi.agent.core.application.service;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.application.ResourceGroupApplicationService;
import com.xspaceagi.agent.core.adapter.application.TableWorkflowForPageApplicationService;
import com.xspaceagi.agent.core.adapter.application.WorkflowApplicationService;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.NodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.TableCustomSqlNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.WorkflowNodeConfig;
import com.xspaceagi.agent.core.infra.rpc.CustomPageRpcService;
import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.enums.TargetTypeEnum;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.exception.BizException;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class TableWorkflowForPageApplicationServiceImpl implements TableWorkflowForPageApplicationService {

    @Resource
    private WorkflowApplicationService workflowApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private CustomPageRpcService customPageRpcService;

    @Resource
    private IAgentRpcService iAgentRpcService;

    @Resource
    private ResourceGroupApplicationService resourceGroupApplicationService;

    @DSTransactional
    @Override
    public PageSqlResultDTO tableNewSql(PageAppNewSqlDTO pageAppNewSqlDTO) {
        WorkflowConfigDto workflowConfigDto = new WorkflowConfigDto();
        workflowConfigDto.setName(pageAppNewSqlDTO.getApiName());
        workflowConfigDto.setDescription(pageAppNewSqlDTO.getDescription());
        workflowConfigDto.setSpaceId(pageAppNewSqlDTO.getSpaceId());
        workflowConfigDto.setCreatorId(RequestContext.get().getUserId());
        Long workflowId = workflowApplicationService.add(workflowConfigDto);

        // 添加分组
        if (StringUtils.isNotBlank(pageAppNewSqlDTO.getGroupName())) {
            List<ResourceGroupDto> resourceGroupDtos = resourceGroupApplicationService.queryList(pageAppNewSqlDTO.getGroupName(), List.of(TargetTypeEnum.Workflow.name()), pageAppNewSqlDTO.getSpaceId());
            if (!resourceGroupDtos.isEmpty()) {
                resourceGroupApplicationService.addResourceToGroup(resourceGroupDtos.get(0).getId(), TargetTypeEnum.Workflow.name(), workflowId);
            } else {
                ResourceGroupDto resourceGroupDto = new ResourceGroupDto();
                resourceGroupDto.setName(pageAppNewSqlDTO.getGroupName());
                resourceGroupDto.setDescription(pageAppNewSqlDTO.getGroupDescription() == null ? "" : pageAppNewSqlDTO.getGroupDescription());
                resourceGroupDto.setIcon("");
                resourceGroupDto.setType(TargetTypeEnum.Workflow.name());
                resourceGroupDto.setSpaceId(pageAppNewSqlDTO.getSpaceId());
                Long groupId = resourceGroupApplicationService.add(resourceGroupDto);
                resourceGroupApplicationService.addResourceToGroup(groupId, TargetTypeEnum.Workflow.name(), workflowId);
            }
        }

        WorkflowNodeAddDto workflowNodeAddDto = new WorkflowNodeAddDto();
        workflowNodeAddDto.setWorkflowId(workflowId);
        workflowNodeAddDto.setType(WorkflowNodeConfig.NodeType.TableSQL);
        workflowNodeAddDto.setTypeId(pageAppNewSqlDTO.getTableId());
        workflowApplicationService.addWorkflowNode(workflowNodeAddDto);

        return tableUpdateSql0(workflowId, pageAppNewSqlDTO.getArgs(), pageAppNewSqlDTO.getSql(), pageAppNewSqlDTO.getProjectId());
    }

    private PageSqlResultDTO tableUpdateSql0(Long workflowId, List<Arg> args0, String sql, String projectId) {
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryById(workflowId);
        WorkflowNodeDto workflowTableNodeDto = workflowConfigDto.getNodes().stream().filter(node -> node.getType().equals(WorkflowNodeConfig.NodeType.TableSQL)).findFirst().orElse(null);
        if (workflowTableNodeDto == null) {
            throw new BizException("Invalid data");
        }
        Long nodeId = workflowTableNodeDto.getId();
        WorkflowNodeDto startNode = workflowConfigDto.getStartNode();
        WorkflowNodeUpdateDto<NodeConfigDto> startNodeUpdateDto = new WorkflowNodeUpdateDto<>();
        startNodeUpdateDto.setNodeId(startNode.getId());
        startNodeUpdateDto.setNextNodeIds(Collections.singletonList(nodeId));
        if (startNode.getNodeConfig() == null) {
            startNodeUpdateDto.setNodeConfig(new NodeConfigDto());
        } else {
            startNodeUpdateDto.setNodeConfig(startNode.getNodeConfig());
        }
        startNodeUpdateDto.getNodeConfig().setInputArgs(args0);
        workflowApplicationService.updateWorkflowNodeConfig(startNodeUpdateDto); // 更新开始节点信息
        workflowApplicationService.updateNextIds(startNode.getId(), Collections.singletonList(nodeId));

        WorkflowNodeDto endNode = workflowConfigDto.getEndNode();

        WorkflowNodeUpdateDto<TableCustomSqlNodeConfigDto> workflowNodeUpdateDto = new WorkflowNodeUpdateDto<>();
        TableCustomSqlNodeConfigDto tableNodeConfigDto = (TableCustomSqlNodeConfigDto) workflowTableNodeDto.getNodeConfig();
        tableNodeConfigDto.setSql(sql);
        if (args0 != null) {
            List<Arg> args = args0.stream().map(arg -> {
                arg.setEnable(true);
                Arg argDto = new Arg();
                BeanUtils.copyProperties(arg, argDto);
                argDto.setBindValueType(Arg.BindValueType.Reference);
                argDto.setBindValue(startNode.getId() + "." + arg.getName());
                return argDto;
            }).collect(Collectors.toList());
            tableNodeConfigDto.setInputArgs(args);
        }
        workflowNodeUpdateDto.setNextNodeIds(Collections.singletonList(endNode.getId()));
        workflowNodeUpdateDto.setNodeId(nodeId);
        workflowNodeUpdateDto.setNodeConfig(tableNodeConfigDto);
        workflowApplicationService.updateWorkflowNodeConfig(workflowNodeUpdateDto);
        workflowApplicationService.updateNextIds(nodeId, Collections.singletonList(endNode.getId()));

        WorkflowNodeUpdateDto<NodeConfigDto> endNodeUpdateDto = new WorkflowNodeUpdateDto<>();
        endNodeUpdateDto.setNodeId(endNode.getId());
        if (endNode.getNodeConfig() == null) {
            endNodeUpdateDto.setNodeConfig(new NodeConfigDto());
        } else {
            endNodeUpdateDto.setNodeConfig(endNode.getNodeConfig());
        }
        endNodeUpdateDto.getNodeConfig().setOutputArgs(List.of(
                Arg.builder().name("outputList").dataType(DataTypeEnum.Array_Object).bindValueType(Arg.BindValueType.Reference).bindValue(nodeId + ".outputList").build(),
                Arg.builder().name("rowNum").dataType(DataTypeEnum.Array_Object).bindValueType(Arg.BindValueType.Reference).bindValue(nodeId + ".rowNum").build()
        ));

        workflowApplicationService.updateWorkflowNodeConfig(endNodeUpdateDto);
        PublishApplySubmitDto publishApplySubmitDto = new PublishApplySubmitDto();
        publishApplySubmitDto.setTargetType(Published.TargetType.Workflow);
        publishApplySubmitDto.setTargetId(workflowId);
        publishApplySubmitDto.setRemark("");
        publishApplySubmitDto.setCategory("");
        PublishApplySubmitDto.PublishItem publishItem = new PublishApplySubmitDto.PublishItem();
        publishItem.setScope(Published.PublishScope.Space);
        publishItem.setAllowCopy(0);
        publishItem.setOnlyTemplate(0);
        publishItem.setSpaceId(workflowConfigDto.getSpaceId());
        publishApplySubmitDto.setItems(List.of(publishItem));
        publishApplicationService.publishOrApply(publishApplySubmitDto);
        customPageRpcService.bindDataSource(RequestContext.get().getUserId(), Long.parseLong(projectId), "Workflow", workflowId);
        PageSqlResultDTO pageSqlResultDTO = new PageSqlResultDTO();
        pageSqlResultDTO.setApiId(workflowId);
        String authorizationPrompt = """
                调用API时需要在header中传递referer，以下是根据系统环境变量构建referer
                {PLATFORM_BASE_URL}/page/{PROJECT_ID}-{AGENT_ID}/dev/
                
                **页面上禁止使用/api/v1/4sandbox开头的API**，/api/v1/4sandbox开头的API仅用作内部调试。
                /api/v1/4sandbox/page/ 仅仅用于创建API后的调试使用，禁止在页面上使用。页面上使用时需要转换：/api/v1/4sandbox/page/ -> /api/page/
                """;
        pageSqlResultDTO.setApiSchema(authorizationPrompt + iAgentRpcService.queryApiSchema(TargetTypeEnum.Workflow, workflowId, Long.parseLong(projectId)).getData());
        pageSqlResultDTO.setApiSchema(pageSqlResultDTO.getApiSchema().replace("/api/page/", "/api/v1/4sandbox/page/"));
        return pageSqlResultDTO;
    }

    @DSTransactional
    @Override
    public PageSqlResultDTO tableUpdateSql(PageAppUpdateSqlDTO pageAppUpdateSqlDTO) {
        return tableUpdateSql0(pageAppUpdateSqlDTO.getApiId(), pageAppUpdateSqlDTO.getArgs(), pageAppUpdateSqlDTO.getSql(), pageAppUpdateSqlDTO.getProjectId());
    }
}
