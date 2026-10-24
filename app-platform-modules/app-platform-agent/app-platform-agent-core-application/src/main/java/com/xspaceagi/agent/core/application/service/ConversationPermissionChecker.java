package com.xspaceagi.agent.core.application.service;

import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentConfig;
import com.xspaceagi.agent.core.domain.service.AgentDomainService;
import com.xspaceagi.sandbox.sdk.server.ISandboxConfigRpcService;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class ConversationPermissionChecker {

    @Resource
    private ConversationApplicationService conversationApplicationService;
    @Resource
    private SpacePermissionService spacePermissionService;
    @Resource
    private ISandboxConfigRpcService sandboxConfigRpcService;

    @Resource
    private AgentDomainService agentDomainService;

    /**
     * 校验当前用户对会话的访问权限：
     * 1. cId 非空校验
     * 2. 会话存在性校验
     * 3. 如果是会话创建者，直接通过
     * 4. 如果不是创建者，校验开发空间权限
     *
     * @param cId 会话ID
     * @return 会话创建者用户ID
     */
    public Long check(Long cId) {
        return check(cId, null);
    }

    public ConversationDto checkAndResponseConversation(Long cId) {
        return check0(cId, null);
    }

    /**
     * 校验当前用户对会话的访问权限，并在传入 customTargetDir 时校验：
     * 云端电脑（sandboxServerId 为空或 -1）不允许指定；个人电脑仅会话属主本人可指定
     * （个人沙箱属于属主，协作者可访问会话但不能借 customTargetDir 读属主机器的任意目录）。
     */
    public Long check(Long cId, String customTargetDir) {
        return check0(cId, customTargetDir).getUserId();
    }

    private ConversationDto check0(Long cId, String customTargetDir) {
        if (cId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "cId");
        }
        ConversationDto conversation = conversationApplicationService.getConversationByCid(cId);
        if (conversation == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentConversationNotFound);
        }
        validateCustomTargetDir(conversation, customTargetDir);
        Long conversationUserId = conversation.getUserId();
        if (RequestContext.get().getUserId().equals(conversationUserId)) {
            return conversation;
        }
        checkUserConversationPermission(RequestContext.get().getUserId(), conversation);
        return conversation;
    }

    public void checkUserConversationPermission(Long userId, ConversationDto conversation) {
        if (conversation.getUserId().equals(userId)) {
            return;
        }
        String cacheKey = "ucp:" + RequestContext.get().getUserId() + "-" + conversation.getId();
        Object o = SimpleJvmHashCache.getHash("conversation.permission.check", cacheKey);
        if (o != null) {
            return;
        }
        Long devSpaceId = conversation.getDevSpaceId();
        if (devSpaceId != null) {
            spacePermissionService.checkSpaceUserPermission(devSpaceId, userId);
        } else if (YesOrNoEnum.Y.getKey().equals(conversation.getDevMode())) {
            AgentConfig agentConfig = agentDomainService.queryById(conversation.getAgentId());
            if (agentConfig != null) {
                spacePermissionService.checkSpaceUserPermission(agentConfig.getSpaceId(), userId);
            } else {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
            }
        } else {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }

        // 临时缓存权限1分钟，避免段时间内过多的重复校验
        SimpleJvmHashCache.putHash("conversation.permission.check", cacheKey, conversation.getId(), 60);
    }

    /**
     * 校验 customTargetDir 是否允许用于指定会话（不校验会话访问权限，适用于已有独立鉴权逻辑的接口；
     * 但 customTargetDir 本身带属主校验：仅会话属主本人的个人沙箱可指定，云端/协作者/匿名一律拒绝）。
     */
    public void checkCustomTargetDir(Long cId, String customTargetDir) {
        if (cId == null || StringUtils.isBlank(customTargetDir)) {
            return;
        }
        ConversationDto conversation = conversationApplicationService.getConversationByCid(cId);
        if (conversation == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentConversationNotFound);
        }
        validateCustomTargetDir(conversation, customTargetDir);
    }

    private void validateCustomTargetDir(ConversationDto conversation, String customTargetDir) {
        if (StringUtils.isBlank(customTargetDir)) {
            return;
        }
        // 静态文件端点在 auth.excludePath 中可匿名访问，RequestContext 可能为空；匿名一律不放开自定义目录
        Long currentUserId = RequestContext.get() == null ? null : RequestContext.get().getUserId();
        if (currentUserId == null) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.validationFailedWithDetail,
                    "customTargetDir is only supported on your own conversation");
        }
        // 个人沙箱目录仅会话属主本人可指定：协作者虽有会话访问权，但不能借此读写属主机器的任意目录
        if (!conversation.getUserId().equals(currentUserId)) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.validationFailedWithDetail,
                    "customTargetDir is only supported on your own conversation");
        }
        String sandboxServerId = conversation.getSandboxServerId();
        if (sandboxServerId == null || "-1".equals(sandboxServerId)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "customTargetDir is not supported for cloud computer");
        }
        long sandboxConfigId;
        try {
            sandboxConfigId = Long.parseLong(sandboxServerId);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "customTargetDir is not supported for cloud computer");
        }
        SandboxConfigRpcDto sandboxConfigDto = sandboxConfigRpcService.queryById(sandboxConfigId);
        if (sandboxConfigDto == null || sandboxConfigDto.getScope() != SandboxScopeEnum.USER) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                    "customTargetDir is not supported for cloud computer");
        }
        // 双保险：沙箱确属当前登录用户（与文件系统浏览 requireSandboxBrowsePermission 同款校验，防会话行沙箱指向异常数据）
        if (!Objects.equals(sandboxConfigDto.getUserId(), currentUserId)) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.validationFailedWithDetail,
                    "customTargetDir is only supported on your own sandbox");
        }
    }
}
