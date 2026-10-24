package com.xspaceagi.userapp.application.service.impl;

import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.userapp.application.service.IUserPrivateServerApplicationService;
import com.xspaceagi.userapp.domain.model.UserAppModel;
import com.xspaceagi.userapp.domain.model.UserPrivateServerModel;
import com.xspaceagi.userapp.domain.service.IUserAppDomainService;
import com.xspaceagi.userapp.domain.service.IUserPrivateServerDomainService;
import com.xspaceagi.userapp.sdk.dto.UserAppDeployType;
import com.xspaceagi.userapp.sdk.dto.UserPrivateServerDto;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

/**
 * 私服登记应用服务
 */
@Slf4j
@Service
public class UserPrivateServerApplicationServiceImpl implements IUserPrivateServerApplicationService {

    /** 健康检查端点 */
    private static final String HEALTH_PATH = "/health";

    /** 容器版本端点：响应 data.version */
    private static final String VERSION_PATH = "/version";

    /** 探测专用：连接/读取均短超时，失败即置 offline */
    private static final RestTemplate HEALTH_REST_TEMPLATE = buildHealthRestTemplate();

    @Resource
    private IUserPrivateServerDomainService userPrivateServerDomainService;
    @Resource
    private IUserAppDomainService userAppDomainService;

    @Override
    public UserPrivateServerDto register(UserPrivateServerModel model) {
        model.setTenantId(RequestContext.get().getTenantId());
        model.setOwnerUserId(RequestContext.get().getUserId());
        return toDto(userPrivateServerDomainService.register(model));
    }

    @Override
    public UserPrivateServerDto update(Long operatorUserId, UserPrivateServerModel model) {
        return toDto(userPrivateServerDomainService.update(operatorUserId, model));
    }

    @Override
    public void delete(Long operatorUserId, Long id) {
        userPrivateServerDomainService.delete(operatorUserId, id);
    }

    @Override
    public List<UserPrivateServerDto> listMine() {
        return userPrivateServerDomainService
                .listByOwner(RequestContext.get().getTenantId(), RequestContext.get().getUserId())
                .stream().map(this::toDto).toList();
    }

    @Override
    public UserPrivateServerDto get(Long operatorUserId, Long id) {
        // 详情先过属主校验
        UserPrivateServerModel model = userPrivateServerDomainService.getById(id);
        if (model == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappPrivateServerNotFound);
        }
        if (!model.getOwnerUserId().equals(operatorUserId)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappPrivateServerNoPermission);
        }
        return toDto(model);
    }

    @Override
    public boolean checkHealth(Long operatorUserId, Long id) {
        UserPrivateServerDto server = get(operatorUserId, id);
        boolean online;
        try {
            // 探测管理端口的/health
            HEALTH_REST_TEMPLATE.exchange(server.getScheme() + "://" + server.getHost() + ":"
                    + server.getAgentPort() + HEALTH_PATH, HttpMethod.GET, new HttpEntity<>(Map.of()),
                    String.class);
            online = true;
        } catch (Exception e) {
            log.warn("[UserPrivateServer] health check failed, id={}, host={}, cause={}",
                    id, server.getHost(), e.getMessage());
            online = false;
        }
        userPrivateServerDomainService.markStatus(id, online);
        if (online) {
            fetchVersion(id, server);
        }
        return online;
    }

    /** 在线时顺带回填容器版本 */
    private void fetchVersion(Long id, UserPrivateServerDto server) {
        try {
            ResponseEntity<Map<String, Object>> response = HEALTH_REST_TEMPLATE.exchange(
                    server.getScheme() + "://" + server.getHost() + ":" + server.getAgentPort() + VERSION_PATH,
                    HttpMethod.GET, new HttpEntity<>(Map.of()),
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            Object data = response.getBody() == null ? null : response.getBody().get("data");
            Object version = data instanceof Map<?, ?> dataMap ? dataMap.get("version") : null;
            if (version != null) {
                userPrivateServerDomainService.saveVersion(id, String.valueOf(version));
            } else {
                log.warn("[UserPrivateServer] version missing in response, id={}, host={}", id, server.getHost());
            }
        } catch (Exception e) {
            log.warn("[UserPrivateServer] version fetch failed, id={}, host={}, cause={}",
                    id, server.getHost(), e.getMessage());
        }
    }

    @Override
    public void setAppDeployTarget(Long appId, UserAppDeployType deployType, Long deployServerId,
            Long operatorUserId) {
        UserAppModel app = userAppDomainService.getById(appId);
        if (app == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappProjectNotFound);
        }
        if (deployType == UserAppDeployType.Private) {
            if (deployServerId == null || deployServerId <= 0) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappPrivateServerInvalidParam);
            }
            UserPrivateServerModel server = userPrivateServerDomainService.getById(deployServerId);
            if (server == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappPrivateServerNotFound);
            }
            // 属主 fail-closed：私服只能绑定到登记人本人创建的应用（与部署时 resolvePrivateServerHandle 同口径）。
            // 报错按场景区分：自己的私服+他人的应用 vs 他人的私服，避免"无权使用"误导私服属主
            if (!server.getTenantId().equals(app.getTenantId())
                    || !server.getOwnerUserId().equals(app.getCreatorId())) {
                log.warn("[UserPrivateServer] deploy target rejected, appId={}, serverId={}, serverOwner={}, appCreator={}, operator={}",
                        appId, deployServerId, server.getOwnerUserId(), app.getCreatorId(), operatorUserId);
                if (server.getOwnerUserId().equals(operatorUserId)) {
                    // 操作者就是私服属主：卡在应用创建者是别人
                    throw new BizException(BizExceptionCodeEnum.userappPrivateServerNoPermission.getCode(),
                            "私有服务器仅可绑定至登记人本人创建的应用，当前应用由其他成员创建");
                }
                throw new BizException(BizExceptionCodeEnum.userappPrivateServerNoPermission.getCode(),
                        "仅可使用本人登记的私有服务器");
            }
        } else {
            // platform：存云端占位 -1
            deployServerId = UserAppDeployType.CLOUD_SERVER_ID;
        }
        userAppDomainService.updateDeployTarget(appId, deployType, deployServerId);
        log.info("[UserPrivateServer] app deploy target set, appId={}, type={}, serverId={}, operator={}",
                appId, deployType, deployServerId, operatorUserId);
    }

    private static RestTemplate buildHealthRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(3_000);
        return new RestTemplate(factory);
    }

    private UserPrivateServerDto toDto(UserPrivateServerModel model) {
        if (model == null) {
            return null;
        }
        UserPrivateServerDto dto = new UserPrivateServerDto();
        dto.setId(model.getId());
        dto.setName(model.getName());
        dto.setScheme(model.getScheme());
        dto.setHost(model.getHost());
        dto.setAgentPort(model.getAgentPort());
        dto.setVncPort(model.getVncPort());
        dto.setFileServerPort(model.getFileServerPort());
        dto.setApiKey(model.getApiKey());
        dto.setStatus(model.getStatus() == null ? null : model.getStatus().getValue());
        dto.setVersion(model.getVersion());
        dto.setLastHealthCheck(model.getLastHealthCheck());
        dto.setCreated(model.getCreated());
        dto.setModified(model.getModified());
        return dto;
    }
}
