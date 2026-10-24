package com.xspaceagi.userapp.domain.service;

import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.userapp.domain.gateway.IUserAppSandboxClient;
import com.xspaceagi.userapp.domain.gateway.UserAppSandboxHandle;
import com.xspaceagi.userapp.domain.model.UserAppModel;
import com.xspaceagi.userapp.domain.model.UserPrivateServerModel;
import com.xspaceagi.userapp.domain.repository.IUserPrivateServerRepository;
import com.xspaceagi.userapp.sdk.dto.UserAppDeployType;
import com.xspaceagi.userapp.sdk.dto.UserPrivateServerStatus;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

/**
 * userapp 项目下游端点解析统一入口（全局见 SandboxConfigEndpoints）。
 *
 * dev 恒为项目绑定沙箱；prod 跟随部署目标（私服部署走属主私服，平台部署走项目绑定沙箱）。
 */
@Slf4j
@Service
public class UserAppEndpointResolver {

    @Resource
    private IUserPrivateServerRepository userPrivateServerRepository;
    @Resource
    private IUserAppSandboxClient userAppSandboxClient;

    /** dev 端点：项目绑定沙箱（无绑定直接报错，不回退） */
    public UserAppSandboxHandle resolveDev(UserAppModel project) {
        return userAppSandboxClient.resolveByProject(project);
    }

    /** 按环境分发：调用方把各自的环境词汇（UserAppStage / 代理 ENV_* 等）归一为 prod 布尔后走此入口 */
    public UserAppSandboxHandle resolve(UserAppModel project, boolean prod) {
        return prod ? resolveProd(project) : resolveDev(project);
    }

    /**
     * 任务行按受理时记录的 sandboxId 寻址：
     * 空白与 -1 视为未记录、格式非法、配置缺失/属主不匹配/不可用均返回 null，由调用方决定回退或报错。
     */
    public UserAppSandboxHandle resolveBySandboxId(String sandboxId, Long expectedUserId) {
        if (StringUtils.isBlank(sandboxId) || "-1".equals(sandboxId)) {
            return null;
        }
        long parsedSandboxId;
        try {
            parsedSandboxId = Long.parseLong(sandboxId);
        } catch (NumberFormatException e) {
            log.warn("[UserAppEndpointResolver] invalid sandboxId: {}", sandboxId);
            return null;
        }
        return userAppSandboxClient.resolveBySandboxId(parsedSandboxId, expectedUserId);
    }

    /** prod 端点：跟随部署目标——私服部署→属主私服（fail-closed 校验在下方私服解析）；平台部署→项目绑定沙箱 */
    public UserAppSandboxHandle resolveProd(UserAppModel project) {
        if (UserAppDeployType.Private != project.getDeployType()) {
            return userAppSandboxClient.resolveByProject(project);
        }
        if (project.getDeployServerId() == null || project.getDeployServerId() <= 0) {
            // 脏数据直接报错
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappPrivateServerInvalidParam);
        }
        return resolvePrivateServerHandle(project.getDeployServerId(), project.getTenantId(), project.getCreatorId());
    }

    /** 私服行→部署 handle（fail-closed）：不存在/非属主/不在线均抛错 */
    private UserAppSandboxHandle resolvePrivateServerHandle(Long privateServerId, Long expectedTenantId, Long expectedUserId) {
        UserPrivateServerModel model = userPrivateServerRepository.getById(privateServerId);
        if (model == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappPrivateServerNotFound);
        }
        // 属主 fail-closed：私服是用户私有资产，不匹配直接拒绝
        if (!Objects.equals(model.getTenantId(), expectedTenantId)
                || !Objects.equals(model.getOwnerUserId(), expectedUserId)) {
            log.warn("[UserPrivateServer] tenant/owner mismatch rejected, serverId={}, owner={}, expectedTenant={}, expectedUser={}",
                    privateServerId, model.getOwnerUserId(), expectedTenantId, expectedUserId);
            // 与 setAppDeployTarget 同口径：expectedUserId 为应用创建者，不匹配说明绑定后应用归属变化等数据漂移
            throw new BizException(BizExceptionCodeEnum.userappPrivateServerNoPermission.getCode(),
                    "私有服务器属主与应用创建者不一致，无法部署：请应用创建者重新绑定部署目标");
        }
        if (model.getStatus() != UserPrivateServerStatus.Online) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappPrivateServerOffline);
        }
        String scheme = StringUtils.defaultIfBlank(model.getScheme(), "http");
        return new UserAppSandboxHandle(model.getId().toString(),
                scheme + "://" + model.getHost() + ":" + model.getAgentPort(),
                StringUtils.defaultString(model.getApiKey()),
                model.getVncPort() != null && model.getVncPort() > 0
                        ? scheme + "://" + model.getHost() + ":" + model.getVncPort()
                        : null);
    }
}
