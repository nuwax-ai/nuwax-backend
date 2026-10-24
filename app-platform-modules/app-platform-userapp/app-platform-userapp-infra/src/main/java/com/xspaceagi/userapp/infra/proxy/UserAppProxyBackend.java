package com.xspaceagi.userapp.infra.proxy;

import java.net.MalformedURLException;
import java.net.URL;

import org.apache.commons.lang3.StringUtils;

import com.xspaceagi.userapp.domain.gateway.UserAppSandboxHandle;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 代理后端目标
 */
@Slf4j
@Data
public class UserAppProxyBackend {

    private String scheme;

    private String host;

    private int port;

    /** 下游 x-api-key（沙箱侧按 user_id/app_id 二次校验应用归属） */
    private String apiKey;

    /** 项目创建者ID */
    private Long ownerUserId;

    public static UserAppProxyBackend of(UserAppSandboxHandle handle, Long ownerUserId) {
        // 用户侧流量（app/dbx/vnc/ttyd/ime/audio）走沙箱 vnc 端口
        if (StringUtils.isBlank(handle.getVncBaseUrl())) {
            log.warn("[UserAppProxy] sandbox vnc entry missing, sandboxId={}", handle.getSandboxId());
            return null;
        }
        try {
            URL url = new URL(handle.getVncBaseUrl());
            UserAppProxyBackend backend = new UserAppProxyBackend();
            backend.setScheme(url.getProtocol());
            backend.setHost(url.getHost());
            backend.setPort(url.getPort() == -1 ? ("https".equals(url.getProtocol()) ? 443 : 80) : url.getPort());
            backend.setApiKey(StringUtils.defaultString(handle.getApiKey()));
            backend.setOwnerUserId(ownerUserId);
            return backend;
        } catch (MalformedURLException e) {
            log.warn("[UserAppProxy] invalid sandbox vncBaseUrl: {}", handle.getVncBaseUrl());
            return null;
        }
    }
}
