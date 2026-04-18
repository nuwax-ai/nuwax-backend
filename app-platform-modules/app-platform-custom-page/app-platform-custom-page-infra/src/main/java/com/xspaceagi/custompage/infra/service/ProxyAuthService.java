package com.xspaceagi.custompage.infra.service;

import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.dto.AgentInfoDto;
import com.xspaceagi.agent.core.sdk.dto.AgentPublishedPermissionDto;
import com.xspaceagi.agent.core.sdk.dto.ReqResult;
import com.xspaceagi.custompage.infra.vo.BackendVo;
import com.xspaceagi.custompage.infra.vo.ProxyAuthVo;
import com.xspaceagi.custompage.sdk.dto.ProxyConfig;
import com.xspaceagi.custompage.sdk.dto.PublishTypeEnum;
import com.xspaceagi.system.application.dto.*;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.infra.dao.entity.SpaceUser;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProxyAuthService {

    @Resource
    private AuthService authService;

    @Resource
    private IAgentRpcService agentRpcService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private ConversationApplicationService conversationApplicationService;

    public boolean initTenantContext(Long agentId) {
        if (agentId == null) {
            return false;
        }

        // 避免一次页面访问里面的资源文件多次调用接口
        AgentInfoDto agentInfoDto = (AgentInfoDto) SimpleJvmHashCache.getHash("proxy.initTenantContext", agentId.toString());
        if (agentInfoDto == null) {
            synchronized (this) {
                if (agentInfoDto == null) {
                    ReqResult<List<AgentInfoDto>> listReqResult = TenantFunctions
                            .callWithIgnoreCheck(() -> agentRpcService.queryAgentInfoList(List.of(agentId)));
                    if (!listReqResult.isSuccess() || listReqResult.getData() == null || listReqResult.getData().isEmpty()) {
                        return false;
                    }
                    agentInfoDto = listReqResult.getData().get(0);
                    SimpleJvmHashCache.putHash("proxy.initTenantContext", agentId.toString(), agentInfoDto, 10);
                }
            }
        }
        TenantConfigDto tenantConfig = (TenantConfigDto) SimpleJvmHashCache.getHash("proxy.initTenantContext.config", "tenantConfig");
        if (tenantConfig == null) {
            synchronized (this) {
                if (tenantConfig == null) {
                    tenantConfig = tenantConfigApplicationService.getTenantConfig(agentInfoDto.getTenantId());
                    SimpleJvmHashCache.putHash("proxy.initTenantContext.config", "tenantConfig", tenantConfig, 10);
                }
            }
        }

        RequestContext requestContext = new RequestContext<>();
        requestContext.setTenantId(agentInfoDto.getTenantId());
        requestContext.setTenantConfig(tenantConfig);
        RequestContext.set(requestContext);
        return true;
    }

    public ProxyAuthVo getProxyAuthVo(String token, Long agentId, ProxyConfig.ProxyEnv env, BackendVo backendVo) {
        String key = "proxy_auth_vo:" + token;
        Object proxyAuthVoCached = SimpleJvmHashCache.getHash("proxy_auth_vo", key);
        if (proxyAuthVoCached != null) {
            return (ProxyAuthVo) proxyAuthVoCached;
        }
        ProxyAuthVo proxyAuthVo;
        synchronized (this) {
            proxyAuthVoCached = SimpleJvmHashCache.getHash("proxy_auth_vo", key);
            if (proxyAuthVoCached != null) {
                return (ProxyAuthVo) proxyAuthVoCached;
            }
            proxyAuthVo = buildProxyAuthVo(token, agentId, env);
            if (env == ProxyConfig.ProxyEnv.prod && backendVo.getPublishType() == PublishTypeEnum.AGENT) {
                // 添加最近使用
                agentApplicationService.addOrUpdateRecentUsed(proxyAuthVo.getUser().getUserId(), backendVo.getDevAgentId());
                // 使用人数增加
                conversationApplicationService.createConversationForPageApp(proxyAuthVo.getUser().getUserId(), backendVo.getDevAgentId());
            }
        }
        // 内存缓存10秒，避免短时间重复查询
        SimpleJvmHashCache.putHash("proxy_auth_vo", key, proxyAuthVo, 10);
        return proxyAuthVo;
    }

    private ProxyAuthVo buildProxyAuthVo(String token, Long agentId, ProxyConfig.ProxyEnv env) {
        ReqResult<List<AgentInfoDto>> listReqResult = TenantFunctions
                .callWithIgnoreCheck(() -> agentRpcService.queryAgentInfoList(List.of(agentId)));
        if (!listReqResult.isSuccess() || listReqResult.getData() == null || listReqResult.getData().isEmpty()) {
            throw new IllegalArgumentException("Error agentId");
        }
        AgentInfoDto agentInfoDto = listReqResult.getData().get(0);
        UserDto loginUserInfo = authService.getLoginUserInfo(token);
        if (loginUserInfo == null) {
            throw BizException.of(ErrorCodeEnum.UNAUTHORIZED_REDIRECT, BizExceptionCodeEnum.customPageProxyAuthFailed);
        }

        RequestContext.get().setUserId(loginUserInfo.getId());
        RequestContext.get().setUser(loginUserInfo);

        TenantDto tenantDto = tenantConfigApplicationService.queryTenantById(agentInfoDto.getTenantId());

        SpaceDto spaceDto = spaceApplicationService.queryById(agentInfoDto.getSpaceId());
        if (spaceDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageProxyNoSpace);
        }

        SpaceUserDto spaceUserDto = spaceApplicationService.querySpaceUser(agentInfoDto.getSpaceId(),
                loginUserInfo.getId());
        if (env == ProxyConfig.ProxyEnv.prod) {
            ReqResult<AgentPublishedPermissionDto> agentPublishedPermissionDtoReqResult = agentRpcService
                    .queryAgentPublishedPermission(agentId);
            if (!agentPublishedPermissionDtoReqResult.getData().isView()) {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
            }
        } else {
            if (spaceUserDto == null && loginUserInfo.getRole() != User.Role.Admin) {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
            }
        }

        ProxyAuthVo.Role role = ProxyAuthVo.Role.User;
        if (spaceUserDto != null) {
            if (spaceUserDto.getRole() == SpaceUser.Role.Owner || spaceUserDto.getRole() == SpaceUser.Role.Admin) {
                role = ProxyAuthVo.Role.SpaceAdmin;
            } else if (loginUserInfo.getId().equals(agentInfoDto.getCreatorId())) {
                role = ProxyAuthVo.Role.AgentCreator;
            } else {
                role = ProxyAuthVo.Role.SpaceUser;
            }
        }

        ProxyAuthVo.Agent agent = ProxyAuthVo.Agent.builder()
                .agentId(agentInfoDto.getId())
                .name(agentInfoDto.getName())
                .icon(agentInfoDto.getIcon())
                .build();

        ProxyAuthVo.Space space = ProxyAuthVo.Space.builder()
                .spaceId(spaceDto.getId())
                .name(spaceDto.getName())
                .icon(spaceDto.getIcon())
                .build();

        ProxyAuthVo.User user = ProxyAuthVo.User.builder()
                .userId(loginUserInfo.getId())
                .userName(loginUserInfo.getUserName())
                .nickName(loginUserInfo.getNickName())
                .uid(loginUserInfo.getUid())
                .avatar(loginUserInfo.getAvatar())
                .role(role)
                .build();
        ProxyAuthVo proxyAuthVo = new ProxyAuthVo();
        proxyAuthVo.setUser(user);
        proxyAuthVo.setAgent(agent);
        proxyAuthVo.setSpace(space);
        proxyAuthVo.setTenant(new ProxyAuthVo.Tenant(tenantDto.getId(), tenantDto.getName(), tenantDto.getDescription()));
        return proxyAuthVo;
    }

    public String getTokenByTicket(String ticket) {
        return authService.getTokenByTicket(ticket);
    }
}
