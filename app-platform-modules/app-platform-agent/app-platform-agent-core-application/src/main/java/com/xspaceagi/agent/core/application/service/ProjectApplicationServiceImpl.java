package com.xspaceagi.agent.core.application.service;

import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.recommend.TargetRecommendResponse;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.TargetRecommend;
import com.xspaceagi.agent.core.spec.enums.CodeLanguageEnum;
import com.xspaceagi.agent.core.spec.enums.PluginTypeEnum;
import com.xspaceagi.custompage.sdk.ICustomPageRpcService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProjectApplicationServiceImpl implements ProjectApplicationService {

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private SkillApplicationService skillApplicationService;

    @Resource
    private PluginApplicationService pluginApplicationService;

    @Resource
    private ICustomPageRpcService iCustomPageRpcService;

    @Resource
    private AgentWorkspaceApplicationService agentWorkspaceApplicationService;

    @Resource
    private RecommendApplicationService recommendApplicationService;

    @Transactional
    @Override
    public ProjectCreateResultDTO createProject(ProjectCreateDTO projectCreateDTO) {
        List<TargetRecommendResponse> targetRecommendResponses = recommendApplicationService.list(TargetRecommend.RecType.ChatBoxNav.name(), TargetRecommend.TargetType.Agent.name());
        Map<String, TargetRecommendResponse> recommendResponseMap = targetRecommendResponses.stream().collect(Collectors.toMap(TargetRecommendResponse::getFunctionType, targetRecommendResponse -> targetRecommendResponse, (old, v) -> v));
        if (projectCreateDTO.getTargetType() == Published.TargetType.Agent) {
            return createProjectForAgent(projectCreateDTO, recommendResponseMap.get(TargetRecommend.FunctionType.AgentDev.name()));
        }

        if (projectCreateDTO.getTargetType() == Published.TargetType.Skill) {
            return createProjectForSkill(projectCreateDTO, recommendResponseMap.get(TargetRecommend.FunctionType.SkillDev.name()));
        }

        if (projectCreateDTO.getTargetType() == Published.TargetType.Plugin) {
            return createProjectForPlugin(projectCreateDTO, recommendResponseMap.get(TargetRecommend.FunctionType.PluginDev.name()));
        }

        if (projectCreateDTO.getTargetType() == Published.TargetType.PageApp) {
            return createProjectForCustomPage(projectCreateDTO, recommendResponseMap.get(TargetRecommend.FunctionType.PageAppDev.name()));
        }

        throw new IllegalArgumentException("Invalid target type");
    }

    private ProjectCreateResultDTO createProjectForCustomPage(ProjectCreateDTO projectCreateDTO, TargetRecommendResponse targetRecommendResponse) {
        Assert.notNull(targetRecommendResponse, "Develop agent not config");
        String projectId = iCustomPageRpcService.create(projectCreateDTO.getCreatorId(), projectCreateDTO.getSpaceId(), projectCreateDTO.getName());
        ProjectCreateResultDTO projectCreateResultDTO = new ProjectCreateResultDTO();
        projectCreateResultDTO.setTargetId(projectId);
        projectCreateResultDTO.setTargetType(Published.TargetType.PageApp);
        projectCreateResultDTO.setConversationId(null);
        conversationApplicationService.createConversationForProjectDevelopment(targetRecommendResponse.getTenantId(),
                projectCreateDTO.getCreatorId(), projectCreateDTO.getSpaceId(), targetRecommendResponse.getTargetId(), Published.TargetType.PageApp.name(), Long.parseLong(projectId));
        return projectCreateResultDTO;
    }

    private ProjectCreateResultDTO createProjectForPlugin(ProjectCreateDTO projectCreateDTO, TargetRecommendResponse targetRecommendResponse) {
        Assert.notNull(targetRecommendResponse, "Develop agent not config");
        PluginAddDto pluginConfigDto = new PluginAddDto();
        pluginConfigDto.setName(projectCreateDTO.getName());
        pluginConfigDto.setDescription(projectCreateDTO.getName());
        pluginConfigDto.setType(PluginTypeEnum.CODE);
        pluginConfigDto.setCodeLang(CodeLanguageEnum.JavaScript);
        pluginConfigDto.setCreatorId(projectCreateDTO.getCreatorId());
        pluginConfigDto.setSpaceId(projectCreateDTO.getSpaceId());
        Long pluginId = pluginApplicationService.add(pluginConfigDto);
        Long devAgentConversationId = conversationApplicationService.createConversationForProjectDevelopment(targetRecommendResponse.getTenantId(),
                projectCreateDTO.getCreatorId(), projectCreateDTO.getSpaceId(), targetRecommendResponse.getTargetId(), Published.TargetType.Plugin.name(), pluginId).getId();
        PluginUpdateDto<?> pluginUpdateDto = new PluginUpdateDto<>();
        pluginUpdateDto.setId(pluginId);
        pluginUpdateDto.setDevAgentConversationId(devAgentConversationId);
        pluginApplicationService.update(pluginUpdateDto);
        ProjectCreateResultDTO projectCreateResultDTO = new ProjectCreateResultDTO();
        projectCreateResultDTO.setTargetId(pluginId.toString());
        projectCreateResultDTO.setTargetType(Published.TargetType.Plugin);
        projectCreateResultDTO.setConversationId(devAgentConversationId);
        return projectCreateResultDTO;
    }

    private ProjectCreateResultDTO createProjectForSkill(ProjectCreateDTO projectCreateDTO, TargetRecommendResponse targetRecommendResponse) {
        Assert.notNull(targetRecommendResponse, "Develop agent not config");
        SkillConfigDto skillConfigDto = new SkillConfigDto();
        skillConfigDto.setName(projectCreateDTO.getName());
        skillConfigDto.setCreatorId(projectCreateDTO.getCreatorId());
        skillConfigDto.setSpaceId(projectCreateDTO.getSpaceId());
        Long skillId = skillApplicationService.add(skillConfigDto);
        Long devSkillConversationId = conversationApplicationService.createConversationForProjectDevelopment(targetRecommendResponse.getTenantId(),
                projectCreateDTO.getCreatorId(), projectCreateDTO.getSpaceId(), targetRecommendResponse.getTargetId(), Published.TargetType.Skill.name(), skillId).getId();

        skillConfigDto = new SkillConfigDto();
        skillConfigDto.setId(skillId);
        skillConfigDto.setDevAgentConversationId(devSkillConversationId);
        skillApplicationService.update(skillConfigDto, false);

        ProjectCreateResultDTO projectCreateResultDTO = new ProjectCreateResultDTO();
        projectCreateResultDTO.setTargetId(skillId.toString());
        projectCreateResultDTO.setTargetType(Published.TargetType.Skill);
        projectCreateResultDTO.setConversationId(devSkillConversationId);
        try {
            agentWorkspaceApplicationService.initProjectTemplate(InitProjectTemplateDto.builder()
                    .cId(devSkillConversationId)
                    .projectType(InitProjectTemplateDto.ProjectType.SKILL)
                    .userId(projectCreateDTO.getCreatorId())
                    .build());
        } catch (Exception e) {
            log.error("Init skill template error", e);
            if (!log.isDebugEnabled()) {
                throw e;
            }
        }
        return projectCreateResultDTO;
    }

    private ProjectCreateResultDTO createProjectForAgent(ProjectCreateDTO projectCreateDTO, TargetRecommendResponse targetRecommendResponse) {
        Assert.notNull(targetRecommendResponse, "Develop agent not config");
        InitProjectTemplateDto.ProgrammingLanguage programmingLanguage = projectCreateDTO.getProgrammingLanguage() == null ? InitProjectTemplateDto.ProgrammingLanguage.TYPESCRIPT : projectCreateDTO.getProgrammingLanguage();
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setName(projectCreateDTO.getName());
        agentConfigDto.setType(AgentConfigDto.AgentType.TaskAgent.name());
        agentConfigDto.setSubType(AgentConfigDto.AgentSubType.Custom.name());
        agentConfigDto.setExtra(Map.of("createdFrom", "VibeCoding", "programmingLanguage", programmingLanguage));
        agentConfigDto.setSpaceId(projectCreateDTO.getSpaceId());
        agentConfigDto.setCreatorId(projectCreateDTO.getCreatorId());
        Long agentId = agentApplicationService.add(agentConfigDto);
        ConversationDto conversationDto = conversationApplicationService.createConversationForProjectDevelopment(targetRecommendResponse.getTenantId(),
                projectCreateDTO.getCreatorId(), projectCreateDTO.getSpaceId(), targetRecommendResponse.getTargetId(), Published.TargetType.Agent.name(), agentId);
        Long devAgentConversationId = conversationDto.getId();
        agentConfigDto = new AgentConfigDto();
        agentConfigDto.setDevAgentConversationId(devAgentConversationId);
        agentConfigDto.setId(agentId);
        agentApplicationService.update(agentConfigDto);
        conversationApplicationService.createConversation(projectCreateDTO.getCreatorId(), agentId, true);
        ProjectCreateResultDTO projectCreateResultDTO = new ProjectCreateResultDTO();
        projectCreateResultDTO.setTargetId(agentId.toString());
        projectCreateResultDTO.setTargetType(Published.TargetType.Agent);
        projectCreateResultDTO.setConversationId(devAgentConversationId);
        try {
            agentWorkspaceApplicationService.initProjectTemplate(InitProjectTemplateDto.builder()
                    .cId(devAgentConversationId)
                    .projectType(InitProjectTemplateDto.ProjectType.AGENT)
                    .programmingLanguage(programmingLanguage)
                    .userId(projectCreateDTO.getCreatorId())
                    .build());
        } catch (Exception e) {
            log.error("Init agent template error", e);
            if (!log.isDebugEnabled()) {
                throw e;
            }
        }
        return projectCreateResultDTO;
    }
}
