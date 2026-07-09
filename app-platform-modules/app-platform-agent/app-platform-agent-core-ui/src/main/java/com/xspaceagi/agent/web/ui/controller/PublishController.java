package com.xspaceagi.agent.web.ui.controller;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.dto.recommend.TargetRecommendResponse;
import com.xspaceagi.agent.core.adapter.repository.entity.PublishApply;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.TargetRecommend;
import com.xspaceagi.agent.core.domain.service.PublishDomainService;
import com.xspaceagi.agent.web.ui.controller.base.BaseController;
import com.xspaceagi.agent.web.ui.controller.dto.*;
import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.sandbox.SandboxUtils;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.I18nUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.PUBLISH_AUDIT_QUERY_DETAIL;

@Tag(name = "发布相关接口")
@RestController
@RequestMapping("/api/publish")
@Slf4j
public class PublishController extends BaseController {

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private PluginApplicationService pluginApplicationService;

    @Resource
    private WorkflowApplicationService workflowApplicationService;

    @Resource
    private SkillApplicationService skillApplicationService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private PublishDomainService publishDomainService;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private AgentWorkspaceApplicationService agentWorkspaceApplicationService;

    @Resource
    private RecommendApplicationService recommendApplicationService;

    @Resource
    private IFileAccessService iFileAccessService;

    // 因为此接口是聚合接口，不通过@RequireResource 校验权限，在接口实现中区分类型后校验权限
    @Operation(summary = "提交发布申请")
    @RequestMapping(path = "/apply", method = RequestMethod.POST)
    public ReqResult<String> publishApply(HttpServletRequest request,
                                          @RequestBody PublishApplySubmitDto publishApplySubmitDto) {
        if (SandboxUtils.isSandboxRequest(request)) {
            if (StringUtils.isBlank(publishApplySubmitDto.getCategory())) {
                publishApplySubmitDto.setCategory("Other");
            }
            if (CollectionUtils.isNotEmpty(publishApplySubmitDto.getItems())) {
                for (PublishApplySubmitDto.PublishItem item : publishApplySubmitDto.getItems()) {
                    if (item != null && item.getScope() == Published.PublishScope.Space && item.getSpaceId() == null) {
                        Long personalSpaceId = spaceApplicationService.getPersonalSpaceId(RequestContext.get().getUserId());
                        item.setSpaceId(personalSpaceId);
                    }
                }
            }
        }
        String message = publishApplicationService.publishOrApply(publishApplySubmitDto);
        return ReqResult.create(ReqResult.SUCCESS, message, message);
    }

    //发布列表查询

    @Operation(summary = "查询指定智能体插件或工作流已发布列表")
    @RequestMapping(path = "/item/list", method = RequestMethod.POST)
    public ReqResult<List<PublishItemDto>> queryPublishItems(@RequestBody PublishQueryDto publishQueryDto) {
        Assert.notNull(publishQueryDto.getTargetType(), "targetType is required");
        Assert.notNull(publishQueryDto.getTargetId(), "targetId is required");
        publishApplicationService.checkPermissionAndReturnTargetConfig(publishQueryDto.getTargetType(), publishQueryDto.getTargetId());
        List<PublishApply> publishApplyList = publishDomainService.queryPublishApplyingList(publishQueryDto.getTargetType(), publishQueryDto.getTargetId());
        List<Published> publishedList = publishDomainService.queryPublishedList(publishQueryDto.getTargetType(), List.of(publishQueryDto.getTargetId()));
        //publishApplyList提取userIds
        List<Long> userIds = publishApplyList.stream().map(PublishApply::getApplyUserId).collect(Collectors.toList());
        userIds.addAll(publishedList.stream().map(Published::getUserId).toList());
        List<UserDto> users = userApplicationService.queryUserListByIds(userIds);
        Map<Long, UserDto> userMap = users.stream().collect(Collectors.toMap(UserDto::getId, userDto -> userDto));
        List<PublishItemDto> publishItemDtoList = publishedList.stream().map(published -> {
            PublishItemDto publishItemDto = new PublishItemDto();
            publishItemDto.setPublishId(published.getId());
            publishItemDto.setPublishStatus(Published.PublishStatus.Published);
            publishItemDto.setScope(published.getScope());
            publishItemDto.setPublishDate(published.getModified());
            publishItemDto.setAllowCopy(published.getAllowCopy());
            publishItemDto.setOnlyTemplate(published.getOnlyTemplate());
            publishItemDto.setSpaceId(published.getSpaceId());
            publishItemDto.setCategory(published.getCategory());
            UserDto userDto = userMap.get(published.getUserId());
            if (userDto != null) {
                PublishUserDto publishUserDto = PublishUserDto.builder()
                        .userId(userDto.getId())
                        .userName(userDto.getUserName())
                        .nickName(userDto.getNickName())
                        .avatar(userDto.getAvatar())
                        .build();
                publishItemDto.setPublishUser(publishUserDto);
            }
            return publishItemDto;
        }).collect(Collectors.toList());

        List<PublishItemDto> publishItemDtoList0 = publishApplyList.stream().map(publishApply -> {
            PublishItemDto publishItemDto = new PublishItemDto();
            publishItemDto.setPublishStatus(publishApply.getPublishStatus());
            publishItemDto.setScope(publishApply.getScope());
            publishItemDto.setPublishDate(publishApply.getModified());
            publishItemDto.setAllowCopy(publishApply.getAllowCopy());
            publishItemDto.setOnlyTemplate(publishApply.getOnlyTemplate());
            publishItemDto.setCategory(publishApply.getCategory());
            UserDto userDto = userMap.get(publishApply.getApplyUserId());
            if (userDto != null) {
                PublishUserDto publishUserDto = PublishUserDto.builder()
                        .userId(userDto.getId())
                        .userName(userDto.getUserName())
                        .nickName(userDto.getNickName())
                        .avatar(userDto.getAvatar())
                        .build();
                publishItemDto.setPublishUser(publishUserDto);
            }
            return publishItemDto;
        }).toList();
        publishItemDtoList.addAll(0, publishItemDtoList0);
        List<SpaceDto> spaceDtos = spaceApplicationService.queryByIds(publishItemDtoList.stream().map(PublishItemDto::getSpaceId).collect(Collectors.toList()));
        //以spaceId为key转map
        Map<Long, SpaceDto> spaceIdMap = spaceDtos.stream().collect(Collectors.toMap(SpaceDto::getId, spaceDto -> spaceDto));
        publishItemDtoList.forEach(publishItemDto -> {
            String onlyTemplateDesc = Objects.equals(publishItemDto.getOnlyTemplate(), YesOrNoEnum.Y.getKey()) ? I18nUtil.systemMessage("Backend.Publish.TemplateOnly") : "";
            if (publishItemDto.getScope() == Published.PublishScope.Space) {
                publishItemDto.setSpaceId(publishItemDto.getSpaceId());
                SpaceDto spaceDto = spaceIdMap.get(publishItemDto.getSpaceId());
                if (spaceDto != null) {
                    publishItemDto.setDescription(I18nUtil.systemMessage("Backend.Publish.SpaceSquare", spaceDto.getName()) + onlyTemplateDesc);
                }
            } else {
                publishItemDto.setDescription(I18nUtil.systemMessage("Backend.Publish.SystemSquare") + onlyTemplateDesc);
            }
        });
        return ReqResult.success(publishItemDtoList);
    }

    @Operation(summary = "智能体、插件、工作流模板复制")
    @RequestMapping(path = "/template/copy", method = RequestMethod.POST)
    public ReqResult<Long> templateCopy(@RequestBody TemplateCopyDto templateCopyDto) {
        PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(templateCopyDto.getTargetType(), templateCopyDto.getTargetId());
        if (!publishedPermissionDto.isCopy()) {
            return ReqResult.error(I18nUtil.systemMessage("Backend.Publish.CopyPermissionDenied"));
        }
        Long id = null;
        spacePermissionService.checkSpaceUserPermission(templateCopyDto.getTargetSpaceId(), RequestContext.get().getUserId());
        if (templateCopyDto.getTargetType() == Published.TargetType.Agent) {
            AgentConfigDto agentConfigDto = agentApplicationService.queryPublishedConfigForExecute(templateCopyDto.getTargetId());
            id = agentApplicationService.copyAgent(RequestContext.get().getUserId(), agentConfigDto, templateCopyDto.getTargetSpaceId());
        }
        if (templateCopyDto.getTargetType() == Published.TargetType.Plugin) {
            PluginDto pluginConfigDto = pluginApplicationService.queryPublishedPluginConfig(templateCopyDto.getTargetId(), null);
            id = pluginApplicationService.copyPlugin(RequestContext.get().getUserId(), pluginConfigDto, templateCopyDto.getTargetSpaceId());
        }
        if (templateCopyDto.getTargetType() == Published.TargetType.Workflow) {
            WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryPublishedWorkflowConfig(templateCopyDto.getTargetId(), null, false);
            id = workflowApplicationService.copyWorkflow(RequestContext.get().getUserId(), workflowConfigDto, templateCopyDto.getTargetSpaceId());
        }
        if (templateCopyDto.getTargetType() == Published.TargetType.Skill) {
            SkillConfigDto skillConfigDto = skillApplicationService.queryPublishedSkillConfig(templateCopyDto.getTargetId(), null, true);
            if (skillConfigDto == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillOffline);
            }
            if (skillConfigDto.getSpaceId() == null) {
                SkillConfigDto sourceSkill = skillApplicationService.queryById(templateCopyDto.getTargetId(), false);
                if (sourceSkill != null) {
                    skillConfigDto.setSpaceId(sourceSkill.getSpaceId());
                }
            }
            if (skillConfigDto.getSpaceId() == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillConfigParseFailed);
            }

            boolean isSandboxSkill = skillConfigDto.getDevAgentConversationId() != null;
            if (isSandboxSkill) {
                skillConfigDto.setFiles(null);
            }
            id = skillApplicationService.copySkill(skillConfigDto, templateCopyDto.getTargetSpaceId());

            // 沙盒技能复制：需要为新技能创建开发会话，并复制沙箱工作空间
            if (isSandboxSkill) {
                try {
                    TargetRecommendResponse recommendResponse = recommendApplicationService.list(TargetRecommend.RecType.ChatBoxNav.name(), TargetRecommend.TargetType.Agent.name()).stream().filter(targetRecommendResponse -> targetRecommendResponse.getFunctionType().equals(TargetRecommend.FunctionType.SkillDev.name())).findFirst().orElse(null);
                    if (recommendResponse == null) {
                        throw new IllegalArgumentException("Develop agent not config");
                    }

                    Long currentUserId = RequestContext.get().getUserId();

                    // 创建开发会话（新技能的 creatorId 是当前用户）
                    Long newCId = conversationApplicationService.createConversationForProjectDevelopment(
                            RequestContext.get().getTenantId(),
                            currentUserId,
                            templateCopyDto.getTargetSpaceId(),
                            recommendResponse.getTargetId(),
                            Published.TargetType.Skill.name(),
                            id
                    ).getId();

                    // 更新新技能的 devAgentConversationId
                    SkillConfigDto updateDto = new SkillConfigDto();
                    updateDto.setId(id);
                    updateDto.setDevAgentConversationId(newCId);
                    skillApplicationService.update(updateDto, false);

                    // 上传技能文件到新工作空间，并初始化 git
                    agentWorkspaceApplicationService.uploadZipToWorkspace(
                            currentUserId,
                            newCId,
                            skillConfigDto.getZipFileUrl());
                } catch (IllegalArgumentException e) {
                    throw e;
                } catch (Exception e) {
                    log.error("Failed to setup sandbox workspace for copied skill, skillId={}", id, e);
                    throw new RuntimeException("Failed to setup sandbox workspace for copied skill", e);
                }
            }
        }
        return ReqResult.success(id);
    }

    @Operation(summary = "智能体、插件、工作流下架")
    @RequestMapping(path = "/offShelf", method = RequestMethod.POST)
    public ReqResult<Void> offShelf(@RequestBody UserOffShelfDto offShelfDto) {
        Assert.notNull(offShelfDto.getPublishId(), "publishId is required");
        PublishedDto publishedDto = publishApplicationService.queryPublishedById(offShelfDto.getPublishId());
        if (publishedDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentUnpublishFailedAlreadyOffline);
        }
        Long originalSpaceId = null;
        Long creatorId = null;
        if (publishedDto.getTargetType() == Published.TargetType.Agent) {
            AgentConfigDto agentConfigDto = JSON.parseObject(publishedDto.getConfig(), AgentConfigDto.class);
            originalSpaceId = agentConfigDto.getSpaceId();
            creatorId = agentConfigDto.getCreatorId();
        }
        if (publishedDto.getTargetType() == Published.TargetType.Workflow) {
            WorkflowConfigDto workflowConfigDto = JSON.parseObject(publishedDto.getConfig(), WorkflowConfigDto.class);
            originalSpaceId = workflowConfigDto.getSpaceId();
            creatorId = workflowConfigDto.getCreatorId();
        }
        if (publishedDto.getTargetType() == Published.TargetType.Plugin) {
            PluginDto pluginDto = JSON.parseObject(publishedDto.getConfig(), PluginDto.class);
            originalSpaceId = pluginDto.getSpaceId();
            creatorId = pluginDto.getCreatorId();
        }
        if (publishedDto.getTargetType() == Published.TargetType.Skill) {
            SkillConfigDto skillConfigDto = skillApplicationService.queryById(publishedDto.getTargetId(), false);
            if (skillConfigDto != null) {
                originalSpaceId = skillConfigDto.getSpaceId();
                creatorId = skillConfigDto.getCreatorId();
            }
            if (originalSpaceId == null) {
                originalSpaceId = publishedDto.getSpaceId();
            }
            if (creatorId == null && publishedDto.getPublishUser() != null) {
                creatorId = publishedDto.getPublishUser().getUserId();
            }
        }
        //发布者和接受方都可以下架
        try {
            spacePermissionService.checkSpaceAdminPermission(publishedDto.getSpaceId());
        } catch (Exception e) {
            if (creatorId == null || !creatorId.equals(RequestContext.get().getUserId())) {
                spacePermissionService.checkSpaceAdminPermission(originalSpaceId);
            }
        }
        if (offShelfDto.isJustOffShelfTemplate()) {
            publishDomainService.offShelfTemplate(offShelfDto.getPublishId());
            return ReqResult.success();
        }
        OffShelfDto offShelfDto1 = new OffShelfDto();
        offShelfDto1.setPublishId(publishedDto.getId());
        offShelfDto1.setReason("用户自行下架");
        publishApplicationService.offShelf(offShelfDto1);
        return ReqResult.success();
    }

    @RequireResource(PUBLISH_AUDIT_QUERY_DETAIL)
    @Operation(summary = "发布申请中的技能详情接口")
    @RequestMapping(path = "/skill/{skillId}", method = RequestMethod.GET)
    public ReqResult<SkillDetailDto> skillDetail(@PathVariable Long skillId) {
        List<PublishApply> publishApplyList = publishDomainService.queryPublishApplyingList(Published.TargetType.Skill, skillId);
        if (CollectionUtils.isEmpty(publishApplyList)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillOffline);
        }
        PublishApply publishApply = publishApplyList.get(0);
        SkillConfigDto skillConfigDto = skillApplicationService.parsePublishedSkillConfig(publishApply.getConfig(), publishApply.getExt());
        if (skillConfigDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSkillConfigParseFailed);
        }
        SkillDetailDto skillDetailDto = new SkillDetailDto();
        skillDetailDto.setId(skillId);
        skillDetailDto.setName(skillConfigDto.getName());
        skillDetailDto.setDescription(skillConfigDto.getDescription());
        skillDetailDto.setIcon(skillConfigDto.getIcon());
        skillDetailDto.setFiles(skillConfigDto.getFiles());
        if (skillDetailDto.getFiles()!=null){
            skillDetailDto.getFiles().forEach(fileDto -> fileDto.setFileProxyUrl(iFileAccessService.getRealFileUrl(fileDto.getFileProxyUrl())));
        }
        skillDetailDto.setExt(publishApply.getExt());
        skillDetailDto.setRemark(publishApply.getRemark());
        skillDetailDto.setCreated(publishApply.getCreated());
        skillDetailDto.setAllowCopy(publishApply.getAllowCopy());
        skillDetailDto.setCategory(publishApply.getCategory());
        return ReqResult.success(skillDetailDto);
    }

}
