package com.xspaceagi.agent.web.ui.controller;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.PublishApply;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.domain.service.PublishDomainService;
import com.xspaceagi.agent.core.spec.utils.MarkdownExtractUtil;
import com.xspaceagi.agent.web.ui.controller.base.BaseController;
import com.xspaceagi.agent.web.ui.controller.dto.*;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.SpaceUserDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.SysUserPermissionCacheService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.SpaceUser;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

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
    private ModelApplicationService modelApplicationService;

    @Resource
    private SysUserPermissionCacheService sysUserPermissionCacheService;

    // 因为此接口是聚合接口，不通过@RequireResource 校验权限，在接口实现中区分类型后校验权限
    @Operation(summary = "提交发布申请")
    @RequestMapping(path = "/apply", method = RequestMethod.POST)
    public ReqResult<String> publishApply(@RequestBody PublishApplySubmitDto publishApplySubmitDto) {
        //整体有两个地方做权限校验，一是校验有没有权限发布出去；而是校验有没有目标空间的发布权限
        Object targetConfig = checkPermissionAndReturnTargetConfig(publishApplySubmitDto.getTargetType(), publishApplySubmitDto.getTargetId());
        // 按目标类型校验资源权限
        checkPublishResourcePermission(publishApplySubmitDto.getTargetType(), targetConfig);
        Assert.notNull(publishApplySubmitDto.getCategory(), "请选择分类");
        if (CollectionUtils.isEmpty(publishApplySubmitDto.getItems())) {
            throw new BizException("未选择发布范围");
        }
        if (targetConfig instanceof PluginDto || targetConfig instanceof WorkflowConfigDto) {
            String name = targetConfig instanceof PluginDto
                    ? ((PluginDto) targetConfig).getName()
                    : ((WorkflowConfigDto) targetConfig).getName();
            name = name == null ? "" : name;
            try {
                //判断是否为英文
                name = name.trim().toLowerCase().replace(" ", "_").replace("__", "_");
                if (StringUtils.isNotBlank(name) && !name.matches("[a-zA-Z0-9_]+")) {
                    EnNameDto enNameDto = modelApplicationService.call(name, new ParameterizedTypeReference<EnNameDto>() {
                    });
                    if (enNameDto != null && StringUtils.isNotBlank(enNameDto.getEnName())) {
                        if (targetConfig instanceof PluginDto) {
                            ((PluginDto) targetConfig).setFunctionName(enNameDto.getEnName());
                        } else {
                            ((WorkflowConfigDto) targetConfig).setFunctionName(enNameDto.getEnName());
                        }
                    }
                }
            } catch (Exception e) {
                //忽略
                log.error("调用模型转换接口异常", e);
            }
        }
        if (targetConfig instanceof SkillConfigDto skillConfig) {
            List<SkillFileDto> files = skillConfig.getFiles();
            if (CollectionUtils.isNotEmpty(files)) {
                List<SkillFileDto> keyFiles = files.stream().filter(file -> "SKILL.MD".equalsIgnoreCase(file.getName()) && !Boolean.TRUE.equals(file.getIsDir())).toList();
                if (CollectionUtils.isNotEmpty(keyFiles)) {
                    for (SkillFileDto file : keyFiles) {
                        String name = MarkdownExtractUtil.extractFieldValue(file.getContents(), "name");
                        if (StringUtils.isNotBlank(name)) {
                            skillConfig.setEnName(name);
                            break;
                        }
                    }
                }
            }
        }

        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        String name = null;
        String description = null;
        String icon = null;
        Integer publishAudit = YesOrNoEnum.Y.getKey();
        Long spaceId = null;
        String agentType = null;
        if (publishApplySubmitDto.getTargetType() == Published.TargetType.Agent) {
            assert targetConfig instanceof AgentConfigDto;
            AgentConfigDto agentConfigDto = (AgentConfigDto) targetConfig;
            //私有电脑的agent不允许发布到广场
            if (agentConfigDto.getExtra() != null && agentConfigDto.getExtra().get("private") != null
                    && publishApplySubmitDto.getItems().stream().anyMatch(item -> item.getScope() == Published.PublishScope.Tenant)) {
                throw new BizException("私有电脑的智能体不允许发布到广场");
            }

            // 构建代理MCP，id存储在agentConfigDto的extra字段中
            agentApplicationService.buildProxyMcp(agentConfigDto, false);
            name = agentConfigDto.getName();
            description = agentConfigDto.getDescription();
            icon = agentConfigDto.getIcon();
            spaceId = agentConfigDto.getSpaceId();
            publishAudit = tenantConfigDto.getAgentPublishAudit();
            agentType = agentConfigDto.getType();
        }
        if (publishApplySubmitDto.getTargetType() == Published.TargetType.Workflow) {
            assert targetConfig instanceof WorkflowConfigDto;
            WorkflowConfigDto workflowConfigDto = (WorkflowConfigDto) targetConfig;
            name = workflowConfigDto.getName();
            description = workflowConfigDto.getDescription();
            icon = workflowConfigDto.getIcon();
            spaceId = workflowConfigDto.getSpaceId();
            publishAudit = tenantConfigDto.getWorkflowPublishAudit();
        }
        if (publishApplySubmitDto.getTargetType() == Published.TargetType.Plugin) {
            assert targetConfig instanceof PluginDto;
            PluginDto pluginDto = (PluginDto) targetConfig;
            name = pluginDto.getName();
            description = pluginDto.getDescription();
            icon = pluginDto.getIcon();
            spaceId = pluginDto.getSpaceId();
            publishAudit = tenantConfigDto.getPluginPublishAudit();
        }
        if (publishApplySubmitDto.getTargetType() == Published.TargetType.Skill) {
            assert targetConfig instanceof SkillConfigDto;
            SkillConfigDto skillConfigDto = (SkillConfigDto) targetConfig;
            name = skillConfigDto.getName();
            description = skillConfigDto.getDescription();
            icon = skillConfigDto.getIcon();
            spaceId = skillConfigDto.getSpaceId();
            publishAudit = tenantConfigDto.getSkillPublishAudit();
        }

        List<Long> userSpaceIds = obtainAuthSpaceIds();
        //参数检查
        for (PublishApplySubmitDto.PublishItem publishItem : publishApplySubmitDto.getItems()) {
            Assert.notNull(publishItem.getScope(), "发布范围不能为空");
            if (publishItem.getScope() == Published.PublishScope.Space) {
                Assert.notNull(publishItem.getSpaceId(), "空间ID不能为空");
                SpaceDto spaceDto = spaceApplicationService.queryById(publishItem.getSpaceId());
                if (spaceDto == null) {
                    throw new BizException("空间[" + publishItem.getSpaceId() + "]不存在");
                }
                if (!userSpaceIds.contains(publishItem.getSpaceId())) {
                    throw new BizException("无空间[" + publishItem.getSpaceId() + "]发布权限");
                }
                if (!spaceDto.getId().equals(spaceId) && spaceDto.getReceivePublish() != YesOrNoEnum.Y.getKey()) {
                    throw new BizException("空间[" + publishItem.getSpaceId() + "]未开启接收发布功能");
                }
            }
        }

        List<PublishApplyDto> tenantPublishApplyDtos = new ArrayList<>();
        List<PublishApplyDto> spacePublishApplyDtos = new ArrayList<>();
        String message = "发布成功";
        for (PublishApplySubmitDto.PublishItem publishItem : publishApplySubmitDto.getItems()) {
            PublishApplyDto publishApplyDto = new PublishApplyDto();
            publishApplyDto.setApplyUser((UserDto) RequestContext.get().getUser());
            publishApplyDto.setTargetType(publishApplySubmitDto.getTargetType());
            if (agentType != null) {
                publishApplyDto.setTargetSubType(Published.TargetSubType.valueOf(agentType));
            }
            publishApplyDto.setTargetId(publishApplySubmitDto.getTargetId());
            publishApplyDto.setChannels(List.of(Published.PublishChannel.System));
            publishApplyDto.setRemark(publishApplySubmitDto.getRemark());
            publishApplyDto.setName(name);
            publishApplyDto.setDescription(description);
            publishApplyDto.setIcon(icon);
            publishApplyDto.setTargetConfig(targetConfig);
            publishApplyDto.setSpaceId(spaceId);
            publishApplyDto.setScope(publishItem.getScope());
            publishApplyDto.setCategory(publishApplySubmitDto.getCategory());
            publishApplyDto.setAllowCopy(publishItem.getAllowCopy());
            publishApplyDto.setOnlyTemplate(publishItem.getOnlyTemplate());
            if (publishItem.getScope() == Published.PublishScope.Space) {
                publishApplyDto.setSpaceId(publishItem.getSpaceId());
            }
            Long applyId = publishApplicationService.publishApply(publishApplyDto);
            publishApplyDto.setId(applyId);
            if (publishItem.getScope() == Published.PublishScope.Space) {
                spacePublishApplyDtos.add(publishApplyDto);
            } else {
                tenantPublishApplyDtos.add(publishApplyDto);
            }
        }

        if (publishAudit == null || publishAudit.equals(YesOrNoEnum.N.getKey()) || CollectionUtils.isEmpty(tenantPublishApplyDtos)) {
            publishApplicationService.publish(publishApplySubmitDto.getTargetType(), publishApplySubmitDto.getTargetId(), Published.PublishScope.Tenant, tenantPublishApplyDtos);
        } else {
            message = "系统广场发布申请已提交，等待审核中";
        }
        publishApplicationService.publish(publishApplySubmitDto.getTargetType(), publishApplySubmitDto.getTargetId(), Published.PublishScope.Space, spacePublishApplyDtos);
        return ReqResult.create(ReqResult.SUCCESS, message, message);
    }

    //发布列表查询

    @Operation(summary = "查询指定智能体插件或工作流已发布列表")
    @RequestMapping(path = "/item/list", method = RequestMethod.POST)
    public ReqResult<List<PublishItemDto>> queryPublishItems(@RequestBody PublishQueryDto publishQueryDto) {
        Assert.notNull(publishQueryDto.getTargetType(), "targetType不能为空");
        Assert.notNull(publishQueryDto.getTargetId(), "targetId不能为空");
        checkPermissionAndReturnTargetConfig(publishQueryDto.getTargetType(), publishQueryDto.getTargetId());
        List<PublishApply> publishApplyList = publishDomainService.queryPublishApplyingList(publishQueryDto.getTargetType(), publishQueryDto.getTargetId());
        List<Published> publishedList = publishDomainService.queryPublishedList(publishQueryDto.getTargetType(), List.of(publishQueryDto.getTargetId()));
        //publishApplyList提取userIds
        List<Long> userIds = publishApplyList.stream().map(PublishApply::getApplyUserId).collect(Collectors.toList());
        userIds.addAll(publishedList.stream().map(Published::getUserId).collect(Collectors.toList()));
        List<UserDto> userDtos = userApplicationService.queryUserListByIds(userIds);
        Map<Long, UserDto> userMap = userDtos.stream().collect(Collectors.toMap(UserDto::getId, userDto -> userDto));
        List<PublishItemDto> publishItemDtoList = publishedList.stream().map(published -> {
            PublishItemDto publishItemDto = new PublishItemDto();
            publishItemDto.setPublishId(published.getId());
            publishItemDto.setPublishStatus(Published.PublishStatus.Published);
            publishItemDto.setScope(published.getScope());
            publishItemDto.setPublishDate(published.getModified());
            publishItemDto.setAllowCopy(published.getAllowCopy());
            publishItemDto.setOnlyTemplate(published.getOnlyTemplate());
            publishItemDto.setSpaceId(published.getSpaceId());
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
        }).collect(Collectors.toList());
        publishItemDtoList.addAll(0, publishItemDtoList0);
        List<SpaceDto> spaceDtos = spaceApplicationService.queryByIds(publishItemDtoList.stream().map(PublishItemDto::getSpaceId).collect(Collectors.toList()));
        //以spaceId为key转map
        Map<Long, SpaceDto> spaceIdMap = spaceDtos.stream().collect(Collectors.toMap(SpaceDto::getId, spaceDto -> spaceDto));
        publishItemDtoList.forEach(publishItemDto -> {
            String onlyTemplateDesc = Objects.equals(publishItemDto.getOnlyTemplate(), YesOrNoEnum.Y.getKey()) ? "（仅模板）" : "";
            if (publishItemDto.getScope() == Published.PublishScope.Space) {
                publishItemDto.setSpaceId(publishItemDto.getSpaceId());
                SpaceDto spaceDto = spaceIdMap.get(publishItemDto.getSpaceId());
                if (spaceDto != null) {
                    publishItemDto.setDescription("空间广场 - " + spaceDto.getName() + onlyTemplateDesc);
                }
            } else {
                publishItemDto.setDescription("系统广场" + onlyTemplateDesc);
            }
        });
        return ReqResult.success(publishItemDtoList);
    }

    @Operation(summary = "智能体、插件、工作流模板复制")
    @RequestMapping(path = "/template/copy", method = RequestMethod.POST)
    public ReqResult<Long> templateCopy(@RequestBody TemplateCopyDto templateCopyDto) {
        PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(templateCopyDto.getTargetType(), templateCopyDto.getTargetId());
        if (!publishedPermissionDto.isCopy()) {
            return ReqResult.error("无复制权限");
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
            PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Skill, templateCopyDto.getTargetId());
            if (publishedDto == null) {
                throw new BizException("技能不存在或已下架");
            }
            SkillConfigDto skillConfigDto = JSON.parseObject(publishedDto.getConfig(), SkillConfigDto.class);
            if (skillConfigDto == null) {
                throw new BizException("技能配置解析失败");
            }
            id = skillApplicationService.copySkill(skillConfigDto, templateCopyDto.getTargetSpaceId());
        }
        return ReqResult.success(id);
    }

    @Operation(summary = "智能体、插件、工作流下架")
    @RequestMapping(path = "/offShelf", method = RequestMethod.POST)
    public ReqResult<Void> offShelf(@RequestBody UserOffShelfDto offShelfDto) {
        Assert.notNull(offShelfDto.getPublishId(), "publishId不能为空");
        PublishedDto publishedDto = publishApplicationService.queryPublishedById(offShelfDto.getPublishId());
        if (publishedDto == null) {
            throw new BizException("下架失败，未发布或已下架");
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
            SkillConfigDto skillConfigDto = JSON.parseObject(publishedDto.getConfig(), SkillConfigDto.class);
            originalSpaceId = skillConfigDto.getSpaceId();
            creatorId = skillConfigDto.getCreatorId();
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

    /**
     * 根据发布目标类型校验当前用户是否具备相应的发布资源权限
     */
    private void checkPublishResourcePermission(Published.TargetType targetType, Object targetConfig) {
        Long userId = RequestContext.get().getUserId();
        if (userId == null || targetType == null || targetConfig == null) {
            return;
        }

        String resourceCode = null;
        if (targetType == Published.TargetType.Agent) {
            AgentConfigDto agentConfigDto = (AgentConfigDto) targetConfig;
            String type = agentConfigDto.getType();
            if (Published.TargetSubType.PageApp.name().equals(type)) {
                resourceCode = PAGE_APP_PUBLISH.getCode();
            } else {
                resourceCode = AGENT_PUBLISH.getCode();
            }
        } else if (targetType == Published.TargetType.Plugin || targetType == Published.TargetType.Workflow) {
            resourceCode = COMPONENT_LIB_PUBLISH.getCode();
        } else if (targetType == Published.TargetType.Skill) {
            resourceCode = SKILL_PUBLISH.getCode();
        }

        if (resourceCode != null) {
            sysUserPermissionCacheService.checkResourcePermissionAny(userId, List.of(resourceCode));
        }
    }

    private Object checkPermissionAndReturnTargetConfig(Published.TargetType targetType, Long targetId) {
        Assert.notNull(targetType, "targetType不能为空");
        Assert.notNull(targetId, "targetId不能为空");
        Long spaceId = null;
        Long creatorId = null;
        Object targetConfig = null;
        if (targetType == Published.TargetType.Agent) {
            AgentConfigDto agentDto = agentApplicationService.queryById(targetId);
            if (agentDto == null) {
                throw new BizException("Agent不存在");
            }
            spaceId = agentDto.getSpaceId();
            creatorId = agentDto.getCreatorId();
            targetConfig = agentDto;
        }
        if (targetType == Published.TargetType.Plugin) {
            PluginDto pluginDto = pluginApplicationService.queryById(targetId);
            if (pluginDto == null) {
                throw new BizException("Plugin不存在");
            }
            spaceId = pluginDto.getSpaceId();
            creatorId = pluginDto.getCreatorId();
            targetConfig = pluginDto;
        }
        if (targetType == Published.TargetType.Workflow) {
            WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryById(targetId);
            if (workflowConfigDto == null) {
                throw new BizException("Workflow不存在");
            }
            spaceId = workflowConfigDto.getSpaceId();
            creatorId = workflowConfigDto.getCreatorId();
            targetConfig = workflowConfigDto;
        }
        if (targetType == Published.TargetType.Skill) {
            SkillConfigDto skillConfigDto = skillApplicationService.queryById(targetId);
            if (skillConfigDto == null) {
                throw new BizException("Skill不存在");
            }
            spaceId = skillConfigDto.getSpaceId();
            creatorId = skillConfigDto.getCreatorId();
            targetConfig = skillConfigDto;
        }
        Assert.notNull(spaceId, "spaceId不能为空");
        SpaceUserDto spaceUserDto = spaceApplicationService.querySpaceUser(spaceId, RequestContext.get().getUserId());
        if (spaceUserDto == null) {
            throw new BizException("用户无空间发布权限");
        }
        if (creatorId.equals(RequestContext.get().getUserId())) {
            return targetConfig;
        }
        if (spaceUserDto.getRole() == SpaceUser.Role.User) {
            throw new BizException("用户无发布权限");
        }
        return targetConfig;
    }
}
