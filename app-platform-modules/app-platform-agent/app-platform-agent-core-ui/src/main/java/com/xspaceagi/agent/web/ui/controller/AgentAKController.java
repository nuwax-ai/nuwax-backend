package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.web.ui.controller.dto.AKDeleteDto;
import com.xspaceagi.agent.web.ui.controller.dto.AKUpdateDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.service.UserAccessKeyApiService;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
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
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

import static com.xspaceagi.system.spec.enums.ResourceEnum.AGENT_API_KEY;

@Tag(name = "Agent API KEY Management Related Interface")
@RestController
@RequestMapping("/api/agent/ak")
@Slf4j
public class AgentAKController {


    @Resource
    private UserAccessKeyApiService userAccessKeyApiService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @RequireResource(AGENT_API_KEY)
    @Operation(summary = "Add Agent API KEY")
    @RequestMapping(path = "/create/{agentId}", method = RequestMethod.POST)
    public ReqResult<UserAccessKeyDto> addAccessKey(@PathVariable Long agentId) {
        checkAgentPermission(agentId);
        Long userId = RequestContext.get().getUserId();
        UserAccessKeyDto userAccessKeyDto = userAccessKeyApiService.newAccessKey(userId, UserAccessKeyDto.AKTargetType.Agent, agentId.toString());
        UserAccessKeyDto.Creator creator =new UserAccessKeyDto.Creator();
        UserDto userDto =(UserDto) RequestContext.get().getUser();
        creator.setUserName(userDto.getNickName() == null ? userDto.getUserName() : userDto.getNickName());
        creator.setUserId(userId);
        userAccessKeyDto.setCreator(creator);
        userAccessKeyDto.setCreated(new Date());
        return ReqResult.success(userAccessKeyDto);
    }

    @RequireResource(AGENT_API_KEY)
    @Operation(summary = "Update Agent API KEY Whether It Is Development Mode")
    @RequestMapping(path = "/update", method = RequestMethod.POST)
    public ReqResult<Void> updateAccessKey(@RequestBody AKUpdateDto akUpdateDto) {
        AgentConfigDto agentConfigDto = checkAgentPermission(akUpdateDto.getAgentId());
        Long userId = RequestContext.get().getUserId();
        if (agentConfigDto.getCreatorId().equals(userId) || isSpaceAdmin(agentConfigDto.getSpaceId())) {
            userId = null;
        }
        userAccessKeyApiService.updateAgentDevMode(userId, akUpdateDto.getAgentId(), akUpdateDto.getAccessKey(), akUpdateDto.getDevMode());
        return ReqResult.success();
    }

    @RequireResource(AGENT_API_KEY)
    @Operation(summary = "Query Agent API KEY List")
    @RequestMapping(path = "/list/{agentId}", method = RequestMethod.GET)
    public ReqResult<List<UserAccessKeyDto>> list(@PathVariable Long agentId) {
        AgentConfigDto agentConfigDto = checkAgentPermission(agentId);
        Long userId = RequestContext.get().getUserId();
        if (agentConfigDto.getCreatorId().equals(userId) || isSpaceAdmin(agentConfigDto.getSpaceId())) {
            userId = null;
        }
        List<UserAccessKeyDto> userAccessKeyDtos = userAccessKeyApiService.queryAccessKeyList(userId, UserAccessKeyDto.AKTargetType.Agent, agentId.toString());
        return ReqResult.success(userAccessKeyDtos);
    }

    @RequireResource(AGENT_API_KEY)
    @Operation(summary = "Delete Agent API KEY")
    @RequestMapping(path = "/delete", method = RequestMethod.POST)
    public ReqResult<Void> deleteAccessKey(@RequestBody AKDeleteDto akDeleteDto) {
        Assert.notNull(akDeleteDto.getAgentId(), "agentId cannot be null");
        Assert.notNull(akDeleteDto.getAccessKey(), "accessKey cannot be null");
        AgentConfigDto agentConfigDto = checkAgentPermission(akDeleteDto.getAgentId());
        Long userId = RequestContext.get().getUserId();

        if (agentConfigDto.getCreatorId().equals(userId) || isSpaceAdmin(agentConfigDto.getSpaceId())) {
            userAccessKeyApiService.deleteAccessKeyWithAgentId(akDeleteDto.getAgentId(), akDeleteDto.getAccessKey());
            return ReqResult.success();
        }
        return ReqResult.success();
    }

    private boolean isSpaceAdmin(Long spaceId) {
        try {
            spacePermissionService.checkSpaceAdminPermission(spaceId);
            return true;
        } catch (Exception e) {
            //
        }
        return false;
    }

    private AgentConfigDto checkAgentPermission(Long agentId) {
        AgentConfigDto agentDto = agentApplicationService.queryById(agentId);
        if (agentDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentNotFoundAlt);
        }
        spacePermissionService.checkSpaceUserPermission(agentDto.getSpaceId());
        return agentDto;
    }
}
