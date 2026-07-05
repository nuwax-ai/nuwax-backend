package com.xspaceagi.agent.web.ui.controller.base;

import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.sandbox.sdk.server.ISandboxConfigRpcService;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class ConversationPermissionChecker {

    @Resource
    private ConversationApplicationService conversationApplicationService;
    @Resource
    private SpacePermissionService spacePermissionService;
    @Resource
    private ISandboxConfigRpcService sandboxConfigRpcService;

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

    /**
     * 校验当前用户对会话的访问权限，并在传入 customTargetDir 时校验会话电脑类型：
     * 云端电脑（sandboxServerId 为空或 -1）不允许指定 customTargetDir，个人电脑允许。
     */
    public Long check(Long cId, String customTargetDir) {
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
            return conversationUserId;
        }
        Long devSpaceId = conversation.getDevSpaceId();
        if (devSpaceId != null) {
            spacePermissionService.checkSpaceUserPermission(devSpaceId, RequestContext.get().getUserId());
        } else {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }
        return conversationUserId;
    }

    /**
     * 校验 customTargetDir 是否允许用于指定会话（不校验用户权限，适用于已有独立鉴权逻辑的接口）。
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
    }
}
