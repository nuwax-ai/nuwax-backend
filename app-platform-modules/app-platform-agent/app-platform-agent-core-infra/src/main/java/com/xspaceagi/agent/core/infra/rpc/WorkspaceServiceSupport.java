package com.xspaceagi.agent.core.infra.rpc;

import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.system.spec.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * 工作空间 RPC 公共支撑：由会话解析项目上下文，并向 file server 请求附带项目类型/appId。
 * WorkspaceRpcClient、ComputerFileClient 共用。
 */
@Slf4j
public final class WorkspaceServiceSupport {

    /** file server 定位工作空间用的项目类型参数名 */
    public static final String PARAM_SERVICE_TYPE = "serviceType";

    public static final String PARAM_APP_ID = "appId";

    /** 会话工作目录参数名 */
    public static final String PARAM_WORKSPACE_PATH = "workspacePath";

    public static final String HEADER_SERVICE_TYPE = "x-service-type";

    public static final String HEADER_APP_ID = "x-app-id";

    public static final String HEADER_WORKSPACE_TYPE = "x-workspace-type";

    /** 沙箱入口通信鉴权 header */
    public static final String HEADER_API_KEY = "x-api-key";

    /** file server 接口的 workspaceType 参数名（工作空间类型定位） */
    public static final String PARAM_WORKSPACE_TYPE = "workspaceType";

    private WorkspaceServiceSupport() {
    }

    public static WorkspaceServiceContext resolveServiceContext(Long cId, SandboxServerConfig.SandboxServer sandboxServer) {
        ConversationDto conversation = sandboxServer.getCurrentConversation();
        if (conversation == null || StringUtils.isBlank(conversation.getDevTargetType())) {
            return WorkspaceServiceContext.general();
        }
        WorkspaceServiceType serviceType = WorkspaceServiceType.fromDevTargetType(conversation.getDevTargetType());
        String appId = conversation.getDevTargetId();
        // userApp/常规项目：容器按 service_type + app_id 定位项目工作区，项目标识缺失即数据错误
        if ((serviceType == WorkspaceServiceType.USERAPP || serviceType == WorkspaceServiceType.NORMAL_PROJECT)
                && StringUtils.isBlank(appId)) {
            log.error("[workspace-client] cId={} devTargetType={} but devTargetId is blank", cId,
                    conversation.getDevTargetType());
            throw new BizException(serviceType.getWorkspaceType() + " conversation missing project id, cId=" + cId);
        }
        // 文件访问路径（用户级工作目录，可空）：空则不下发，file-server 按类型默认规则定位
        return WorkspaceServiceContext.of(serviceType, appId, StringUtils.trimToNull(conversation.getFileWorkspacePath()));
    }

    /** multipart body 中附带项目类型与 appId（所有类型都传，file server 端用于定位工作空间） */
    public static void applyServiceParams(LinkedMultiValueMap<String, Object> body, WorkspaceServiceContext serviceContext) {
        body.add(PARAM_SERVICE_TYPE, serviceContext.getServiceType().getContainerType());
        body.add(PARAM_WORKSPACE_TYPE, serviceContext.getServiceType().getWorkspaceType());
        if (serviceContext.hasAppId()) {
            body.add(PARAM_APP_ID, serviceContext.getAppId());
        }
        if (serviceContext.hasFileWorkspacePath()) {
            body.add(PARAM_WORKSPACE_PATH, serviceContext.getFileWorkspacePath());
        }
    }

    /** JSON body 中附带项目类型与 appId */
    public static void applyServiceParams(Map<String, Object> requestBody, WorkspaceServiceContext serviceContext) {
        requestBody.put(PARAM_SERVICE_TYPE, serviceContext.getServiceType().getContainerType());
        requestBody.put(PARAM_WORKSPACE_TYPE, serviceContext.getServiceType().getWorkspaceType());
        if (serviceContext.hasAppId()) {
            requestBody.put(PARAM_APP_ID, serviceContext.getAppId());
        }
        if (serviceContext.hasFileWorkspacePath()) {
            requestBody.put(PARAM_WORKSPACE_PATH, serviceContext.getFileWorkspacePath());
        }
    }

    /** GET query 中附带项目类型与 appId */
    public static void applyServiceParams(UriComponentsBuilder builder, WorkspaceServiceContext serviceContext) {
        builder.queryParam(PARAM_SERVICE_TYPE, serviceContext.getServiceType().getContainerType());
        builder.queryParam(PARAM_WORKSPACE_TYPE, serviceContext.getServiceType().getWorkspaceType());
        if (serviceContext.hasAppId()) {
            builder.queryParam(PARAM_APP_ID, serviceContext.getAppId());
        }
        if (serviceContext.hasFileWorkspacePath()) {
            builder.queryParam(PARAM_WORKSPACE_PATH, serviceContext.getFileWorkspacePath());
        }
    }

    /**
     * 容器路由/识别 header（全类型路由）：附带 x-service-type（容器路由与类型识别）、
     * x-workspace-type（工作空间类型）；x-app-id 仅项目类会话（userApp/常规项目等，应用或项目主键）有值时携带。
     */
    public static void applyContainerRouteHeaders(HttpHeaders headers, WorkspaceServiceContext serviceContext) {
        headers.set(HEADER_SERVICE_TYPE, serviceContext.getServiceType().getContainerType());
        headers.set(HEADER_WORKSPACE_TYPE, serviceContext.getServiceType().getWorkspaceType());
        if (serviceContext.hasAppId()) {
            headers.set(HEADER_APP_ID, serviceContext.getAppId());
        }
    }

    public static void applyContainerRouteHeaders(HttpHeaders headers, WorkspaceServiceContext serviceContext,
                                                  SandboxServerConfig.SandboxServer sandboxServer) {
        applyContainerRouteHeaders(headers, serviceContext);
        applySandboxAuthHeader(headers, sandboxServer == null ? null : sandboxServer.getServerApiKey());
    }

    /** 下发沙箱通信鉴权头 x-api-key */
    public static void applySandboxAuthHeader(HttpHeaders headers, String apiKey) {
        headers.set(HEADER_API_KEY, apiKey == null ? "" : apiKey);
    }

    /** pod 容器接口参数名（下游为 snake_case） */
    public static final String POD_SERVICE_TYPE = "service_type";

    public static final String POD_APP_STAGE = "app_stage";

    public static final String POD_APP_ID = "app_id";

    /** app_stage：仅 prod 视为生产环境容器，其余（含空）默认 dev */
    public static String normalizeAppStage(String appStage) {
        return "prod".equalsIgnoreCase(appStage) ? "prod" : "dev";
    }

    /** pod 容器接口 body 参数：service_type 所有类型都传；app_id/app_stage 仅 userapp（下游契约：app_id 与非 userapp 的 service_type 互斥） */
    public static void applyPodServiceParams(Map<String, Object> body, WorkspaceServiceContext serviceContext, String appStage) {
        body.put(POD_SERVICE_TYPE, serviceContext.getServiceType().getContainerType());
        if (serviceContext.isUserApp()) {
            body.put(POD_APP_ID, serviceContext.getAppId());
            body.put(POD_APP_STAGE, normalizeAppStage(appStage));
        }
    }

    /** pod 容器接口 GET query 参数，规则同 body 版本 */
    public static void applyPodServiceParams(UriComponentsBuilder builder, WorkspaceServiceContext serviceContext, String appStage) {
        builder.queryParam(POD_SERVICE_TYPE, serviceContext.getServiceType().getContainerType());
        if (serviceContext.isUserApp()) {
            builder.queryParam(POD_APP_ID, serviceContext.getAppId());
            builder.queryParam(POD_APP_STAGE, normalizeAppStage(appStage));
        }
    }
}
