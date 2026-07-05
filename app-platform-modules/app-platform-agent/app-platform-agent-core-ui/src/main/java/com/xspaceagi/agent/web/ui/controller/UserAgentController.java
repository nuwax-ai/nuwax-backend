package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.application.RecommendApplicationService;
import com.xspaceagi.agent.core.adapter.dto.UserAgentDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.recommend.TargetRecommendResponse;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.TargetRecommend;
import com.xspaceagi.agent.core.adapter.repository.entity.UserTargetRelation;
import com.xspaceagi.agent.core.domain.service.UserTargetRelationDomainService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.AGENT_COLLECT;

@Tag(name = "用户智能体最近使用收藏相关接口")
@RestController
@RequestMapping("/api/user/agent")
@Slf4j
public class UserAgentController {

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private UserTargetRelationDomainService userTargetRelationDomainService;

    @Resource
    private RecommendApplicationService recommendApplicationService;

    @Operation(summary = "查询用户最近编辑的智能体列表")
    @RequestMapping(path = "/edit/list/{size}", method = RequestMethod.GET)
    public ReqResult<List<UserAgentDto>> editList(@PathVariable Integer size) {
        List<UserAgentDto> userAgentDtos = agentApplicationService.queryRecentEditList(RequestContext.get().getUserId(), size);
        userAgentDtos.forEach(userAgentDto -> userAgentDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(userAgentDto.getIcon(), userAgentDto.getName())));
        return ReqResult.success(userAgentDtos);
    }

    @Operation(summary = "查询用户最近使用过的智能体列表")
    @RequestMapping(path = "/used/list/{size}", method = RequestMethod.GET)
    public ReqResult<List<UserAgentDto>> usedList(@PathVariable Integer size, @RequestParam(required = false) Integer pageIndex, @RequestParam(required = false) String kw) {
        List<UserAgentDto> userAgentDtos = agentApplicationService.queryRecentUseList(RequestContext.get().getUserId(), kw, size, pageIndex);
        List<TargetRecommendResponse> list = recommendApplicationService.list(TargetRecommend.RecType.ChatBoxNav.name(), TargetRecommend.TargetType.Agent.name());
        Set<Long> navIds = list.stream().map(TargetRecommendResponse::getTargetId).collect(Collectors.toSet());
        userAgentDtos.forEach(userAgentDto -> userAgentDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(userAgentDto.getIcon(), userAgentDto.getName())));
        userAgentDtos.removeIf(userAgentDto -> navIds.contains(userAgentDto.getAgentId()));
        return ReqResult.success(userAgentDtos);
    }

    @Operation(summary = "删除用户最近智能体使用记录")
    @RequestMapping(path = "/used/delete/{agentId}", method = RequestMethod.POST)
    public ReqResult<Void> deleteUsed(@PathVariable Long agentId) {
        userTargetRelationDomainService.unRecord(RequestContext.get().getUserId(), Published.TargetType.Agent, UserTargetRelation.OpType.Conversation, agentId);
        return ReqResult.success();
    }

    @Operation(summary = "查询用户收藏的智能体列表")
    @RequestMapping(path = "/collect/list/{page}/{size}", method = RequestMethod.GET)
    public ReqResult<List<UserAgentDto>> collectList(@PathVariable Integer page, @PathVariable Integer size) {
        List<UserAgentDto> userAgentDtos = agentApplicationService.queryCollectionList(RequestContext.get().getUserId(), page, size);
        userAgentDtos.forEach(userAgentDto -> userAgentDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(userAgentDto.getIcon(), userAgentDto.getName())));
        return ReqResult.success(userAgentDtos);
    }

    @Operation(summary = "智能体收藏")
    @RequestMapping(path = "/collect/{agentId}", method = RequestMethod.POST)
    public ReqResult<Void> collect(@PathVariable Long agentId) {
        agentApplicationService.collect(RequestContext.get().getUserId(), agentId);
        return ReqResult.success();
    }

    @Operation(summary = "智能体取消收藏")
    @RequestMapping(path = "/unCollect/{agentId}", method = RequestMethod.POST)
    public ReqResult<Void> unCollect(@PathVariable Long agentId) {
        agentApplicationService.unCollect(RequestContext.get().getUserId(), agentId);
        return ReqResult.success();
    }

    @Operation(summary = "查询用户开发智能体收藏列表")
    @RequestMapping(path = "/dev/collect/list/{page}/{size}", method = RequestMethod.GET)
    public ReqResult<List<UserAgentDto>> devCollectList(@PathVariable Integer page, @PathVariable Integer size) {
        return ReqResult.success(agentApplicationService.queryDevCollectionList(RequestContext.get().getUserId(), page, size));
    }

    @RequireResource(AGENT_COLLECT)
    @Operation(summary = "开发智能体收藏")
    @RequestMapping(path = "/dev/collect/{agentId}", method = RequestMethod.POST)
    public ReqResult<Void> devCollect(@PathVariable Long agentId) {
        checkAgentPermission(agentId);
        agentApplicationService.devCollect(RequestContext.get().getUserId(), agentId);
        return ReqResult.success();
    }

    @RequireResource(AGENT_COLLECT)
    @Operation(summary = "取消开发智能体收藏")
    @RequestMapping(path = "/dev/unCollect/{agentId}", method = RequestMethod.POST)
    public ReqResult<Void> unDevCollect(@PathVariable Long agentId) {
        agentApplicationService.unDevCollect(RequestContext.get().getUserId(), agentId);
        return ReqResult.success();
    }

    @Operation(summary = "点赞智能体")
    @RequestMapping(path = "/like/{agentId}", method = RequestMethod.POST)
    public ReqResult<Void> like(@PathVariable Long agentId) {
        agentApplicationService.like(RequestContext.get().getUserId(), agentId);
        return ReqResult.success();
    }

    @Operation(summary = "取消点赞智能体")
    @RequestMapping(path = "/unLike/{agentId}", method = RequestMethod.POST)
    public ReqResult<Void> unLike(@PathVariable Long agentId) {
        agentApplicationService.unLike(RequestContext.get().getUserId(), agentId);
        return ReqResult.success();
    }

    private void checkAgentPermission(Long agentId) {
        AgentConfigDto agentDto = agentApplicationService.queryById(agentId);
        if (agentDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentNotFoundAlt);
        }
        spacePermissionService.checkSpaceUserPermission(agentDto.getSpaceId());
    }
}
