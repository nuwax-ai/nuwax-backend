package com.xspaceagi.agent.core.application.service;

import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.recommend.TargetRecommendResponse;
import com.xspaceagi.agent.core.adapter.repository.entity.TargetRecommend;
import com.xspaceagi.agent.core.spec.enums.CodeLanguageEnum;
import com.xspaceagi.agent.core.spec.enums.PluginTypeEnum;
import com.xspaceagi.custompage.sdk.ICustomPageRpcService;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.userapp.sdk.IUserAppRpcService;
import com.xspaceagi.userapp.sdk.IUserProjectRpcService;
import com.xspaceagi.userapp.sdk.dto.UserAppCreateDto;
import com.xspaceagi.userapp.sdk.dto.UserAppDto;
import com.xspaceagi.userapp.sdk.dto.UserProjectCreateDto;
import com.xspaceagi.userapp.sdk.dto.UserProjectDto;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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
    private IUserAppRpcService iUserAppRpcService;

    @Resource
    private IUserProjectRpcService iUserProjectRpcService;

    @Resource
    private AgentWorkspaceApplicationService agentWorkspaceApplicationService;

    @Resource
    private RecommendApplicationService recommendApplicationService;

    @Transactional
    @Override
    public ProjectCreateResultDTO createProject(ProjectCreateDTO projectCreateDTO) {
        List<TargetRecommendResponse> targetRecommendResponses = recommendApplicationService.list(TargetRecommend.RecType.ChatBoxNav.name(), TargetRecommend.TargetType.Agent.name());
        Map<String, TargetRecommendResponse> recommendResponseMap = targetRecommendResponses.stream().collect(Collectors.toMap(TargetRecommendResponse::getFunctionType, targetRecommendResponse -> targetRecommendResponse, (old, v) -> v));
        if (projectCreateDTO.getTargetType() == ProjectTypeEnum.Agent) {
            return createProjectForAgent(projectCreateDTO, recommendResponseMap.get(TargetRecommend.FunctionType.AgentDev.name()));
        }

        if (projectCreateDTO.getTargetType() == ProjectTypeEnum.Skill) {
            return createProjectForSkill(projectCreateDTO, recommendResponseMap.get(TargetRecommend.FunctionType.SkillDev.name()));
        }

        if (projectCreateDTO.getTargetType() == ProjectTypeEnum.Plugin) {
            return createProjectForPlugin(projectCreateDTO, recommendResponseMap.get(TargetRecommend.FunctionType.PluginDev.name()));
        }

        if (projectCreateDTO.getTargetType() == ProjectTypeEnum.PageApp) {
            return createProjectForCustomPage(projectCreateDTO, recommendResponseMap.get(TargetRecommend.FunctionType.PageAppDev.name()));
        }

        if (projectCreateDTO.getTargetType() == ProjectTypeEnum.UserApp) {
            return createProjectForUserApp(projectCreateDTO, recommendResponseMap.get(TargetRecommend.FunctionType.UserAppDev.name()));
        }

        if (projectCreateDTO.getTargetType() == ProjectTypeEnum.NormalProject) {
            return createProjectForNormalProject(projectCreateDTO, recommendResponseMap.get(TargetRecommend.FunctionType.NormalProjectDev.name()));
        }

        throw new IllegalArgumentException("Invalid target type");
    }

    private ProjectCreateResultDTO createProjectForUserApp(ProjectCreateDTO projectCreateDTO, TargetRecommendResponse targetRecommendResponse) {
        Assert.notNull(targetRecommendResponse, "Develop agent not config");
        UserAppCreateDto userAppCreateDto = new UserAppCreateDto();
        userAppCreateDto.setCreatorId(projectCreateDTO.getCreatorId());
        userAppCreateDto.setSpaceId(projectCreateDTO.getSpaceId());
        userAppCreateDto.setName(projectCreateDTO.getName());
        userAppCreateDto.setSandboxId(projectCreateDTO.getSandboxId());
        userAppCreateDto.setWorkspacePath(projectCreateDTO.getWorkspacePath());
        userAppCreateDto.setDevAgentId(resolveDevAgentId(projectCreateDTO, targetRecommendResponse));
        // TODO 本期暂不支持全栈应用自定义工作目录：放开时删除下面这个报错即可（create 内部已支持绑定）
        if (StringUtils.isNotBlank(projectCreateDTO.getWorkspacePath())) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(),
                    "Custom workspace directory is not supported for full-stack apps");
        }
        UserAppDto project = iUserAppRpcService.create(userAppCreateDto);

        Long devAgentConversationId = conversationApplicationService.createConversationForProjectDevelopment(targetRecommendResponse.getTenantId(),
                projectCreateDTO.getCreatorId(), projectCreateDTO.getSpaceId(), resolveDevAgentId(projectCreateDTO, targetRecommendResponse),
                ProjectTypeEnum.UserApp.name(), project.getId(), projectCreateDTO.getSandboxId(), null).getId();

        ProjectCreateResultDTO projectCreateResultDTO = new ProjectCreateResultDTO();
        projectCreateResultDTO.setTargetId(project.getId().toString());
        projectCreateResultDTO.setTargetType(ProjectTypeEnum.UserApp);
        projectCreateResultDTO.setConversationId(devAgentConversationId);
        return projectCreateResultDTO;
    }

    private ProjectCreateResultDTO createProjectForNormalProject(ProjectCreateDTO projectCreateDTO, TargetRecommendResponse targetRecommendResponse) {
        Assert.notNull(targetRecommendResponse, "Develop agent not config");
        UserProjectCreateDto createDto = new UserProjectCreateDto();
        createDto.setCreatorId(projectCreateDTO.getCreatorId());
        createDto.setSpaceId(projectCreateDTO.getSpaceId());
        createDto.setName(projectCreateDTO.getName());
        createDto.setSandboxId(projectCreateDTO.getSandboxId());
        createDto.setWorkspacePath(projectCreateDTO.getWorkspacePath());
        UserProjectDto project = iUserProjectRpcService.createNormal(createDto);

        Long devAgentConversationId = conversationApplicationService.createConversationForProjectDevelopment(targetRecommendResponse.getTenantId(),
                projectCreateDTO.getCreatorId(), project.getSpaceId(), resolveDevAgentId(projectCreateDTO, targetRecommendResponse),
                ProjectTypeEnum.NormalProject.name(), project.getProjectId(), projectCreateDTO.getSandboxId(), null).getId();

        ProjectCreateResultDTO projectCreateResultDTO = new ProjectCreateResultDTO();
        projectCreateResultDTO.setTargetId(project.getProjectId().toString());
        projectCreateResultDTO.setTargetType(ProjectTypeEnum.NormalProject);
        projectCreateResultDTO.setConversationId(devAgentConversationId);
        return projectCreateResultDTO;
    }

    private ProjectCreateResultDTO createProjectForCustomPage(ProjectCreateDTO projectCreateDTO, TargetRecommendResponse targetRecommendResponse) {
        Assert.notNull(targetRecommendResponse, "Develop agent not config");
        String projectId = iCustomPageRpcService.create(projectCreateDTO.getCreatorId(), projectCreateDTO.getSpaceId(), projectCreateDTO.getName());
        ProjectCreateResultDTO projectCreateResultDTO = new ProjectCreateResultDTO();
        projectCreateResultDTO.setTargetId(projectId);
        projectCreateResultDTO.setTargetType(ProjectTypeEnum.PageApp);
        projectCreateResultDTO.setConversationId(null);
        conversationApplicationService.createConversationForProjectDevelopment(targetRecommendResponse.getTenantId(),
                projectCreateDTO.getCreatorId(), projectCreateDTO.getSpaceId(), resolveDevAgentId(projectCreateDTO, targetRecommendResponse), ProjectTypeEnum.PageApp.name(), Long.parseLong(projectId), projectCreateDTO.getSandboxId(), projectCreateDTO.getWorkspacePath());
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
                projectCreateDTO.getCreatorId(), projectCreateDTO.getSpaceId(), resolveDevAgentId(projectCreateDTO, targetRecommendResponse), ProjectTypeEnum.Plugin.name(), pluginId, projectCreateDTO.getSandboxId(), projectCreateDTO.getWorkspacePath()).getId();
        PluginUpdateDto<?> pluginUpdateDto = new PluginUpdateDto<>();
        pluginUpdateDto.setId(pluginId);
        pluginUpdateDto.setDevAgentConversationId(devAgentConversationId);
        pluginApplicationService.update(pluginUpdateDto);
        ProjectCreateResultDTO projectCreateResultDTO = new ProjectCreateResultDTO();
        projectCreateResultDTO.setTargetId(pluginId.toString());
        projectCreateResultDTO.setTargetType(ProjectTypeEnum.Plugin);
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
                projectCreateDTO.getCreatorId(), projectCreateDTO.getSpaceId(), resolveDevAgentId(projectCreateDTO, targetRecommendResponse), ProjectTypeEnum.Skill.name(), skillId, projectCreateDTO.getSandboxId(), projectCreateDTO.getWorkspacePath()).getId();

        skillConfigDto = new SkillConfigDto();
        skillConfigDto.setId(skillId);
        skillConfigDto.setDevAgentConversationId(devSkillConversationId);
        skillApplicationService.update(skillConfigDto, false);

        ProjectCreateResultDTO projectCreateResultDTO = new ProjectCreateResultDTO();
        projectCreateResultDTO.setTargetId(skillId.toString());
        projectCreateResultDTO.setTargetType(ProjectTypeEnum.Skill);
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
                projectCreateDTO.getCreatorId(), projectCreateDTO.getSpaceId(), resolveDevAgentId(projectCreateDTO, targetRecommendResponse), ProjectTypeEnum.Agent.name(), agentId, projectCreateDTO.getSandboxId(), projectCreateDTO.getWorkspacePath());
        Long devAgentConversationId = conversationDto.getId();
        agentConfigDto = new AgentConfigDto();
        agentConfigDto.setDevAgentConversationId(devAgentConversationId);
        agentConfigDto.setId(agentId);
        agentApplicationService.update(agentConfigDto);
        conversationApplicationService.createConversation(projectCreateDTO.getCreatorId(), agentId, true);
        ProjectCreateResultDTO projectCreateResultDTO = new ProjectCreateResultDTO();
        projectCreateResultDTO.setTargetId(agentId.toString());
        projectCreateResultDTO.setTargetType(ProjectTypeEnum.Agent);
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

    /** 前端传入devAgentId优先 */
    private static Long resolveDevAgentId(ProjectCreateDTO projectCreateDTO, TargetRecommendResponse targetRecommendResponse) {
        return projectCreateDTO.getDevAgentId() != null && projectCreateDTO.getDevAgentId() > 0
                ? projectCreateDTO.getDevAgentId()
                : targetRecommendResponse.getTargetId();
    }
}
