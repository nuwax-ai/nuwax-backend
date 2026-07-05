package com.xspaceagi.agent.core.application.service;

import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.ComponentDto;
import com.xspaceagi.agent.core.adapter.dto.CreatorDto;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.ResourceGroupRelation;
import com.xspaceagi.agent.core.spec.enums.ComponentTypeEnum;
import com.xspaceagi.compose.sdk.service.IComposeDbTableRpcService;
import com.xspaceagi.compose.sdk.vo.define.TableDefineVo;
import com.xspaceagi.knowledge.core.application.service.IKnowledgeConfigApplicationService;
import com.xspaceagi.knowledge.domain.model.KnowledgeConfigModel;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ComponentApplicationServiceImpl implements ComponentApplicationService {

    @Resource
    private WorkflowApplicationService workflowApplicationService;

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private PluginApplicationService pluginApplicationService;

    @Resource
    private IKnowledgeConfigApplicationService iKnowledgeConfigApplicationService;

    @Resource
    private IComposeDbTableRpcService iComposeDbTableRpcService;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private ResourceGroupApplicationService resourceGroupApplicationService;

    @Override
    public List<ComponentDto> getComponentListBySpaceId(Long spaceId, Long groupId, List<Published.TargetType> types) {
        List<ComponentDto> componentDtos = new java.util.ArrayList<>();
        if (types == null || types.contains(Published.TargetType.Workflow)) {
            // 查询工作流配置
            List<WorkflowConfigDto> workflowConfigDtos = workflowApplicationService.queryListBySpaceId(spaceId);
            workflowConfigDtos.forEach(workflowConfigDto -> {
                ComponentDto componentDto = new ComponentDto();
                BeanUtils.copyProperties(workflowConfigDto, componentDto);
                componentDto.setType(ComponentTypeEnum.Workflow);
                componentDtos.add(componentDto);
            });
        }

        if (types == null || types.contains(Published.TargetType.Model)) {
            // 查询模型配置
            List<ModelConfigDto> modelConfigDtos = modelApplicationService.queryModelConfigLisBySpaceId(spaceId);
            modelConfigDtos.forEach(modelConfigDto -> {
                ComponentDto componentDto = new ComponentDto();
                BeanUtils.copyProperties(modelConfigDto, componentDto);
                componentDto.setName(modelConfigDto.getName());
                componentDto.setType(ComponentTypeEnum.Model);
                componentDtos.add(componentDto);
            });
        }

        if (types == null || types.contains(Published.TargetType.Plugin)) {
            List<PluginDto> pluginDtos = pluginApplicationService.queryListBySpaceId(spaceId);
            pluginDtos.forEach(pluginDto -> {
                ComponentDto componentDto = new ComponentDto();
                BeanUtils.copyProperties(pluginDto, componentDto);
                componentDto.setType(ComponentTypeEnum.Plugin);
                componentDto.setExt(pluginDto.getType());
                componentDto.setDevAgentConversationId(pluginDto.getDevAgentConversationId());
                componentDtos.add(componentDto);
            });
        }

        if (types == null || types.contains(Published.TargetType.Knowledge)) {
            List<KnowledgeConfigModel> knowledgeConfigModels = iKnowledgeConfigApplicationService.queryListBySpaceId(spaceId);
            //给知识库补充用户信息
            List<UserDto> userDtoList = userApplicationService.queryUserListByIds(knowledgeConfigModels.stream().map(KnowledgeConfigModel::getCreatorId).distinct().collect(Collectors.toList()));
            //userDtoList转map
            Map<Long, UserDto> userDtoMap = userDtoList.stream().collect(Collectors.toMap(UserDto::getId, userDto -> userDto));
            knowledgeConfigModels.forEach(knowledgeConfigModel -> {
                ComponentDto componentDto = new ComponentDto();
                componentDto.setName(knowledgeConfigModel.getName());
                componentDto.setId(knowledgeConfigModel.getId());
                componentDto.setSpaceId(knowledgeConfigModel.getSpaceId());
                //LocalDateTime转Date
                componentDto.setCreated(Date.from(knowledgeConfigModel.getCreated().atZone(ZoneId.systemDefault()).toInstant()));
                componentDto.setModified(Date.from(knowledgeConfigModel.getModified().atZone(ZoneId.systemDefault()).toInstant()));
                componentDto.setDescription(knowledgeConfigModel.getDescription());
                componentDto.setType(ComponentTypeEnum.Knowledge);
                componentDto.setExt(knowledgeConfigModel.getDataType());
                componentDto.setIcon(knowledgeConfigModel.getIcon());
                componentDto.setCreatorId(knowledgeConfigModel.getCreatorId());
                UserDto userDto = userDtoMap.get(knowledgeConfigModel.getCreatorId());
                if (userDto != null) {
                    componentDto.setCreator(new CreatorDto(userDto.getId(), userDto.getUserName(), userDto.getNickName(), userDto.getAvatar()));
                }
                componentDtos.add(componentDto);
            });
        }

        if (types == null || types.contains(Published.TargetType.Table)) {
            //数据表
            List<TableDefineVo> tableDefineVoDorisDataPage = iComposeDbTableRpcService.queryListBySpaceId(spaceId);
            //给知识库补充用户信息
            List<UserDto> tableUserDtoList = userApplicationService.queryUserListByIds(tableDefineVoDorisDataPage.stream()
                    .map(TableDefineVo::getCreatorId)
                    .distinct()
                    .toList());
            Map<Long, UserDto> tableUserDtoMap = tableUserDtoList.stream().collect(Collectors.toMap(UserDto::getId, userDto -> userDto));

            tableDefineVoDorisDataPage.forEach(tableDefineVo -> {
                ComponentDto componentDto = new ComponentDto();
                componentDto.setName(tableDefineVo.getTableName());
                componentDto.setDescription(tableDefineVo.getTableDescription());
                componentDto.setIcon(tableDefineVo.getIcon());
                componentDto.setId(tableDefineVo.getId());
                componentDto.setSpaceId(tableDefineVo.getSpaceId());
                componentDto.setCreated(Date.from(tableDefineVo.getCreated().atZone(ZoneId.systemDefault()).toInstant()));
                componentDto.setModified(Date.from(tableDefineVo.getModified().atZone(ZoneId.systemDefault()).toInstant()));
                componentDto.setType(ComponentTypeEnum.Table);
                componentDto.setExt(tableDefineVo.getDorisDatabase() + "." + tableDefineVo.getDorisTable());
                UserDto userDto = tableUserDtoMap.get(tableDefineVo.getCreatorId());
                if (userDto != null) {
                    componentDto.setCreator(new CreatorDto(userDto.getId(), userDto.getUserName(), userDto.getNickName(), userDto.getAvatar()));
                }
                componentDto.setCreatorId(tableDefineVo.getCreatorId());
                componentDtos.add(componentDto);
            });
        }
        //componentDtos按照编辑时间从大到小排序
        componentDtos.sort((o1, o2) -> o2.getModified().compareTo(o1.getModified()));

        if (groupId != null) {
            Map<String, ResourceGroupRelation> relationMap = resourceGroupApplicationService.queryGroupRelations(groupId).stream()
                    .collect(Collectors.toMap(resourceGroupRelation -> resourceGroupRelation.getTargetType() + "-" + resourceGroupRelation.getTargetId(),
                            resourceGroupRelation -> resourceGroupRelation, (a, b) -> a));
            componentDtos.removeIf(componentDto -> !relationMap.containsKey(componentDto.getType().name() + "-" + componentDto.getId()));
            componentDtos.forEach(componentDto -> componentDto.setGroupId(groupId));
        } else {
            List<Map<String, Long>> list = componentDtos.stream().map(componentDto -> Map.of(componentDto.getType().name(), componentDto.getId())).collect(Collectors.toList());
            Map<String, ResourceGroupRelation> relationMap = resourceGroupApplicationService.queryGroupRelations(list).stream()
                    .collect(Collectors.toMap(resourceGroupRelation -> resourceGroupRelation.getTargetType() + "-" + resourceGroupRelation.getTargetId(),
                            resourceGroupRelation -> resourceGroupRelation, (a, b) -> a));
            componentDtos.forEach(componentDto -> componentDto.setGroupId(relationMap.get(componentDto.getType().name() + "-" + componentDto.getId()) != null ? relationMap.get(componentDto.getType().name() + "-" + componentDto.getId()).getGroupId() : null));
        }
        return componentDtos;
    }
}
