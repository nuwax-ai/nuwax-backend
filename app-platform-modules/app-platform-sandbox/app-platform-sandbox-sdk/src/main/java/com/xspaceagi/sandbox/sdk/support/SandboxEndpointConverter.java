package com.xspaceagi.sandbox.sdk.support;

import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigValue;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxServerInfo;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;

/**
 * 沙箱配置 → 服务端点转换（sandbox 上下文所有，纯静态无副作用，各业务上下文共用）。
 * 可用性校验（active / USER 在线 / host 缺失）与 URL 规范化（尾斜杠、端口拼接、端口未配置置 null）。
 * 配置不可用返回 null，由调用方决定日志与兜底；URL 存裸 host:port（路径后缀由调用方拼）。
 */
public final class SandboxEndpointConverter {

    private SandboxEndpointConverter() {
    }

    /**
     * 沙箱服务端点：sandboxId + 三类流量入口（file-server / pod agent / vnc）+ apiKey + 隔离信息。
     * vncBaseUrl 可为 null（配置未暴露 vnc 端口）。
     */
    public record SandboxEndpoint(String sandboxId, String fileBaseUrl, String agentBaseUrl, String vncBaseUrl,
                                  String apiKey, String isolationType, String isolationKey) {
    }

    /** 配置 → 端点；配置缺失/停用/离线/字段缺失返回 null（不记日志，原因排查由调用方负责） */
    public static SandboxEndpoint toEndpoint(SandboxConfigRpcDto dto) {
        if (dto == null || Boolean.FALSE.equals(dto.getIsActive()) || dto.getId() == null) {
            return null;
        }
        String isolationType = dto.getIsolation() == null ? null : dto.getIsolation().name();
        if (dto.getScope() == SandboxScopeEnum.GLOBAL) {
            SandboxConfigValue configValue = dto.getConfigValue();
            if (configValue == null || isBlank(configValue.getHostWithScheme())) {
                return null;
            }
            String hostWithScheme = trimTrailingSlash(configValue.getHostWithScheme());
            return build(dto.getId().toString(), hostWithScheme, configValue.getAgentPort(), configValue.getVncPort(),
                    configValue.getFileServerPort(), configValue.getApiKey(), isolationType, dto.getIsolationKey());
        }
        // USER 级（个人沙箱）：离线或服务器信息缺失视为不可用
        if (!dto.isOnline()) {
            return null;
        }
        SandboxServerInfo info = dto.getSandboxServerInfo();
        if (info == null || isBlank(info.getHost())) {
            return null;
        }
        return build(dto.getId().toString(), info.getScheme() + "://" + info.getHost(), info.getAgentPort(),
                info.getVncPort(), info.getFileServerPort(), info.getApiKey(), isolationType, dto.getIsolationKey());
    }

    private static SandboxEndpoint build(String sandboxId, String hostWithScheme, int agentPort, int vncPort, int fileServerPort,
                                         String apiKey, String isolationType, String isolationKey) {
        return new SandboxEndpoint(sandboxId,
                fileServerPort > 0 ? hostWithScheme + ":" + fileServerPort : null,
                agentPort > 0 ? hostWithScheme + ":" + agentPort : null,
                vncPort > 0 ? hostWithScheme + ":" + vncPort : null,
                apiKey == null ? "" : apiKey,
                isolationType,
                isolationKey);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
