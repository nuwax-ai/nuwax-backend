package com.xspaceagi.agent.core.infra.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.custompage.sdk.ICustomPageRpcService;
import com.xspaceagi.custompage.sdk.dto.CustomPageDto;
import com.xspaceagi.sandbox.sdk.support.SandboxEndpointConverter.SandboxEndpoint;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GitRpcClient {

    @Value("${custom-page.build-server.base-url:}")
    private String configuredBaseUrl;

    @Resource
    private SandboxServerConfigService sandboxServerConfigService;
    @Resource
    private SandboxConfigEndpoints sandboxConfigEndpoints;
    @Resource
    private ICustomPageRpcService iCustomPageRpcService;

    // ======================== 1. init ========================

    public Map<String, Object> initTaskAgent(Long cId, Long userId) {
        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", WorkspaceServiceType.TASK_AGENT.getWorkspaceType());
        params.put("cId", String.valueOf(cId));
        params.put("userId", String.valueOf(userId));
        TaskAgentContext call = applyConversationServiceParams(params, cId);
        return doGet(call, "/git/init", params);
    }

    public Map<String, Object> initPageApp(Long projectId) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", WorkspaceServiceType.PAGEAPP.getWorkspaceType());
        params.put("projectId", String.valueOf(projectId));
        params.put("tenantId", String.valueOf(ctx.tenantId));
        params.put("spaceId", String.valueOf(ctx.spaceId));
        if (ctx.isolationType != null) {
            params.put("isolationType", ctx.isolationType);
        }
        return doGet(ctx, "/git/init", params);
    }

    // ======================== 2. status ========================

    public Map<String, Object> statusTaskAgent(Long cId, Long userId) {
        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", WorkspaceServiceType.TASK_AGENT.getWorkspaceType());
        params.put("cId", String.valueOf(cId));
        params.put("userId", String.valueOf(userId));
        TaskAgentContext call = applyConversationServiceParams(params, cId);
        return doGet(call, "/git/status", params);
    }

    public Map<String, Object> statusPageApp(Long projectId) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", WorkspaceServiceType.PAGEAPP.getWorkspaceType());
        params.put("projectId", String.valueOf(projectId));
        params.put("tenantId", String.valueOf(ctx.tenantId));
        params.put("spaceId", String.valueOf(ctx.spaceId));
        if (ctx.isolationType != null) {
            params.put("isolationType", ctx.isolationType);
        }
        return doGet(ctx, "/git/status", params);
    }

    // ======================== 3. commit ========================

    public Map<String, Object> commitTaskAgent(Long cId, Long userId, String message,
                                               List<String> files, String authorName, String authorEmail) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.TASK_AGENT.getWorkspaceType());
        body.put("cId", cId);
        body.put("userId", userId);
        TaskAgentContext call = applyConversationServiceParamsBody(body, cId);
        body.put("message", message);
        body.put("files", files);
        body.put("authorName", authorName);
        body.put("authorEmail", authorEmail);
        return doPost(call, "/git/commit", body);
    }

    public Map<String, Object> commitPageApp(Long projectId, String message,
                                             List<String> files, String authorName, String authorEmail) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.PAGEAPP.getWorkspaceType());
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("message", message);
        body.put("files", files);
        body.put("authorName", authorName);
        body.put("authorEmail", authorEmail);
        return doPost(ctx, "/git/commit", body);
    }

    // ======================== 4. add ========================

    public Map<String, Object> addTaskAgent(Long cId, Long userId, List<String> files) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.TASK_AGENT.getWorkspaceType());
        body.put("cId", cId);
        body.put("userId", userId);
        TaskAgentContext call = applyConversationServiceParamsBody(body, cId);
        body.put("files", files);
        return doPost(call, "/git/add", body);
    }

    public Map<String, Object> addPageApp(Long projectId, List<String> files) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.PAGEAPP.getWorkspaceType());
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("files", files);
        return doPost(ctx, "/git/add", body);
    }

    // ======================== 5. unstage ========================

    public Map<String, Object> unstageTaskAgent(Long cId, Long userId, List<String> files) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.TASK_AGENT.getWorkspaceType());
        body.put("cId", cId);
        body.put("userId", userId);
        TaskAgentContext call = applyConversationServiceParamsBody(body, cId);
        body.put("files", files);
        return doPost(call, "/git/unstage", body);
    }

    public Map<String, Object> unstagePageApp(Long projectId, List<String> files) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.PAGEAPP.getWorkspaceType());
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("files", files);
        return doPost(ctx, "/git/unstage", body);
    }

    // ======================== 5. discard ========================

    public Map<String, Object> discardTaskAgent(Long cId, Long userId, List<String> files) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.TASK_AGENT.getWorkspaceType());
        body.put("cId", cId);
        body.put("userId", userId);
        TaskAgentContext call = applyConversationServiceParamsBody(body, cId);
        body.put("files", files);
        return doPost(call, "/git/discard", body);
    }

    public Map<String, Object> discardPageApp(Long projectId, List<String> files) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.PAGEAPP.getWorkspaceType());
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("files", files);
        return doPost(ctx, "/git/discard", body);
    }

    // ======================== 6. log ========================

    public Map<String, Object> logTaskAgent(Long cId, Long userId, Integer page, Integer pageSize, String branch, String filePath) {
        int ps = (pageSize != null && pageSize > 0) ? pageSize : 50;
        int pg = (page != null && page > 0) ? page : 1;
        int skip = (pg - 1) * ps;

        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", WorkspaceServiceType.TASK_AGENT.getWorkspaceType());
        params.put("cId", String.valueOf(cId));
        params.put("userId", String.valueOf(userId));
        params.put("maxCount", String.valueOf(ps));
        if (skip > 0) {
            params.put("skip", String.valueOf(skip));
        }
        if (branch != null) {
            params.put("branch", branch);
        }
        if (filePath != null) {
            params.put("filePath", filePath);
        }
        TaskAgentContext call = applyConversationServiceParams(params, cId);
        return doGet(call, "/git/log", params);
    }

    public Map<String, Object> logPageApp(Long projectId, Integer page, Integer pageSize, String branch, String filePath) {
        int ps = (pageSize != null && pageSize > 0) ? pageSize : 50;
        int pg = (page != null && page > 0) ? page : 1;
        int skip = (pg - 1) * ps;

        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", WorkspaceServiceType.PAGEAPP.getWorkspaceType());
        params.put("projectId", String.valueOf(projectId));
        params.put("tenantId", String.valueOf(ctx.tenantId));
        params.put("spaceId", String.valueOf(ctx.spaceId));
        if (ctx.isolationType != null) {
            params.put("isolationType", ctx.isolationType);
        }
        params.put("maxCount", String.valueOf(ps));
        if (skip > 0) {
            params.put("skip", String.valueOf(skip));
        }
        if (branch != null) {
            params.put("branch", branch);
        }
        if (filePath != null) {
            params.put("filePath", filePath);
        }
        return doGet(ctx, "/git/log", params);
    }

    // ======================== 7. diff ========================

    public Map<String, Object> diffTaskAgent(Long cId, Long userId, String source, String from, String to, List<String> paths) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.TASK_AGENT.getWorkspaceType());
        body.put("cId", cId);
        body.put("userId", userId);
        TaskAgentContext call = applyConversationServiceParamsBody(body, cId);
        body.put("source", source);
        body.put("from", from);
        body.put("to", to);
        body.put("paths", paths);
        return doPost(call, "/git/diff", body);
    }

    public Map<String, Object> diffPageApp(Long projectId, String source, String from, String to, List<String> paths) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.PAGEAPP.getWorkspaceType());
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("source", source);
        body.put("from", from);
        body.put("to", to);
        body.put("paths", paths);
        return doPost(ctx, "/git/diff", body);
    }

    // ======================== 7.5 file-content ========================

    public Map<String, Object> fileContentTaskAgent(Long cId, Long userId, String ref, String filePath) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.TASK_AGENT.getWorkspaceType());
        body.put("cId", cId);
        body.put("userId", userId);
        TaskAgentContext call = applyConversationServiceParamsBody(body, cId);
        body.put("ref", ref);
        body.put("filePath", filePath);
        return doPost(call, "/git/file-content", body);
    }

    public Map<String, Object> fileContentPageApp(Long projectId, String ref, String filePath) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.PAGEAPP.getWorkspaceType());
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("ref", ref);
        body.put("filePath", filePath);
        return doPost(ctx, "/git/file-content", body);
    }

    // ======================== 8. reset ========================

    public Map<String, Object> resetTaskAgent(Long cId, Long userId, String target, String mode) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.TASK_AGENT.getWorkspaceType());
        body.put("cId", cId);
        body.put("userId", userId);
        TaskAgentContext call = applyConversationServiceParamsBody(body, cId);
        body.put("target", target);
        body.put("mode", mode);
        return doPost(call, "/git/reset", body);
    }

    public Map<String, Object> resetPageApp(Long projectId, String target, String mode) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.PAGEAPP.getWorkspaceType());
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("target", target);
        body.put("mode", mode);
        return doPost(ctx, "/git/reset", body);
    }

    // ======================== 9. checkout ========================

    public Map<String, Object> checkoutTaskAgent(Long cId, Long userId, String target) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.TASK_AGENT.getWorkspaceType());
        body.put("cId", cId);
        body.put("userId", userId);
        TaskAgentContext call = applyConversationServiceParamsBody(body, cId);
        body.put("target", target);
        return doPost(call, "/git/checkout", body);
    }

    public Map<String, Object> checkoutPageApp(Long projectId, String target) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.PAGEAPP.getWorkspaceType());
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("target", target);
        return doPost(ctx, "/git/checkout", body);
    }

    // ======================== 10. revert ========================

    public Map<String, Object> revertTaskAgent(Long cId, Long userId, String target, String message,
                                                String authorName, String authorEmail) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.TASK_AGENT.getWorkspaceType());
        body.put("cId", cId);
        body.put("userId", userId);
        TaskAgentContext call = applyConversationServiceParamsBody(body, cId);
        body.put("target", target);
        body.put("message", message);
        body.put("authorName", authorName);
        body.put("authorEmail", authorEmail);
        return doPost(call, "/git/revert", body);
    }

    public Map<String, Object> revertPageApp(Long projectId, String target, String message,
                                              String authorName, String authorEmail) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.PAGEAPP.getWorkspaceType());
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("target", target);
        body.put("message", message);
        body.put("authorName", authorName);
        body.put("authorEmail", authorEmail);
        return doPost(ctx, "/git/revert", body);
    }

    // ======================== 11. tags ========================

    public Map<String, Object> tagsTaskAgent(Long cId, Long userId) {
        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", WorkspaceServiceType.TASK_AGENT.getWorkspaceType());
        params.put("cId", String.valueOf(cId));
        params.put("userId", String.valueOf(userId));
        TaskAgentContext call = applyConversationServiceParams(params, cId);
        return doGet(call, "/git/tags", params);
    }

    public Map<String, Object> tagsPageApp(Long projectId) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", WorkspaceServiceType.PAGEAPP.getWorkspaceType());
        params.put("projectId", String.valueOf(projectId));
        params.put("tenantId", String.valueOf(ctx.tenantId));
        params.put("spaceId", String.valueOf(ctx.spaceId));
        if (ctx.isolationType != null) {
            params.put("isolationType", ctx.isolationType);
        }
        return doGet(ctx, "/git/tags", params);
    }

    // ======================== 12. tag-create ========================

    public Map<String, Object> tagCreateTaskAgent(Long cId, Long userId, String tagName, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.TASK_AGENT.getWorkspaceType());
        body.put("cId", cId);
        body.put("userId", userId);
        TaskAgentContext call = applyConversationServiceParamsBody(body, cId);
        body.put("tagName", tagName);
        body.put("message", message);
        return doPost(call, "/git/tag-create", body);
    }

    public Map<String, Object> tagCreatePageApp(Long projectId, String tagName, String message) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.PAGEAPP.getWorkspaceType());
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("tagName", tagName);
        body.put("message", message);
        return doPost(ctx, "/git/tag-create", body);
    }

    // ======================== 13. tag-delete ========================

    public Map<String, Object> tagDeleteTaskAgent(Long cId, Long userId, String tagName) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.TASK_AGENT.getWorkspaceType());
        body.put("cId", cId);
        body.put("userId", userId);
        TaskAgentContext call = applyConversationServiceParamsBody(body, cId);
        body.put("tagName", tagName);
        return doPost(call, "/git/tag-delete", body);
    }

    public Map<String, Object> tagDeletePageApp(Long projectId, String tagName) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.PAGEAPP.getWorkspaceType());
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("tagName", tagName);
        return doPost(ctx, "/git/tag-delete", body);
    }

    // ======================== 14. branches ========================

    public Map<String, Object> branchesTaskAgent(Long cId, Long userId) {
        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", WorkspaceServiceType.TASK_AGENT.getWorkspaceType());
        params.put("cId", String.valueOf(cId));
        params.put("userId", String.valueOf(userId));
        TaskAgentContext call = applyConversationServiceParams(params, cId);
        return doGet(call, "/git/branches", params);
    }

    public Map<String, Object> branchesPageApp(Long projectId) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", WorkspaceServiceType.PAGEAPP.getWorkspaceType());
        params.put("projectId", String.valueOf(projectId));
        params.put("tenantId", String.valueOf(ctx.tenantId));
        params.put("spaceId", String.valueOf(ctx.spaceId));
        if (ctx.isolationType != null) {
            params.put("isolationType", ctx.isolationType);
        }
        return doGet(ctx, "/git/branches", params);
    }

    // ======================== 15. branch-create ========================

    public Map<String, Object> branchCreateTaskAgent(Long cId, Long userId, String branchName, String startPoint) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.TASK_AGENT.getWorkspaceType());
        body.put("cId", cId);
        body.put("userId", userId);
        TaskAgentContext call = applyConversationServiceParamsBody(body, cId);
        body.put("branchName", branchName);
        body.put("startPoint", startPoint);
        return doPost(call, "/git/branch-create", body);
    }

    public Map<String, Object> branchCreatePageApp(Long projectId, String branchName, String startPoint) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.PAGEAPP.getWorkspaceType());
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("branchName", branchName);
        body.put("startPoint", startPoint);
        return doPost(ctx, "/git/branch-create", body);
    }

    // ======================== 16. branch-switch ========================

    public Map<String, Object> branchSwitchTaskAgent(Long cId, Long userId, String branchName) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.TASK_AGENT.getWorkspaceType());
        body.put("cId", cId);
        body.put("userId", userId);
        TaskAgentContext call = applyConversationServiceParamsBody(body, cId);
        body.put("branchName", branchName);
        return doPost(call, "/git/branch-switch", body);
    }

    public Map<String, Object> branchSwitchPageApp(Long projectId, String branchName) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.PAGEAPP.getWorkspaceType());
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("branchName", branchName);
        return doPost(ctx, "/git/branch-switch", body);
    }

    // ======================== 17. branch-delete ========================

    public Map<String, Object> branchDeleteTaskAgent(Long cId, Long userId, String branchName, Boolean force) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.TASK_AGENT.getWorkspaceType());
        body.put("cId", cId);
        body.put("userId", userId);
        TaskAgentContext call = applyConversationServiceParamsBody(body, cId);
        body.put("branchName", branchName);
        if (force != null) {
            body.put("force", force);
        }
        return doPost(call, "/git/branch-delete", body);
    }

    public Map<String, Object> branchDeletePageApp(Long projectId, String branchName, Boolean force) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", WorkspaceServiceType.PAGEAPP.getWorkspaceType());
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("branchName", branchName);
        if (force != null) {
            body.put("force", force);
        }
        return doPost(ctx, "/git/branch-delete", body);
    }

    // ======================== 内部类 ========================

    /** taskAgent 调用上下文：目标沙箱（base URL）+ 会话项目上下文（userApp 路由 header 注入） */
    private static class TaskAgentContext {
        final SandboxServerConfig.SandboxServer server;
        final WorkspaceServiceContext serviceContext;

        TaskAgentContext(SandboxServerConfig.SandboxServer server, WorkspaceServiceContext serviceContext) {
            this.server = server;
            this.serviceContext = serviceContext;
        }
    }

    private static class PageAppContext {
        final String baseUrl;
        final Long tenantId;
        final Long spaceId;
        final String isolationType;
        final String apiKey;

        PageAppContext(String baseUrl, Long tenantId, Long spaceId, String isolationType, String apiKey) {
            this.baseUrl = baseUrl;
            this.tenantId = tenantId;
            this.spaceId = spaceId;
            this.isolationType = isolationType;
            this.apiKey = apiKey;
        }
    }

    // ======================== taskAgent URL 解析 ========================

    private String getBaseUrl(SandboxServerConfig.SandboxServer server) {
        return server.getServerFileUrl() + "/api";
    }

    private SandboxServerConfig.SandboxServer requireTaskAgentServer(Long cId) {
        SandboxServerConfig.SandboxServer sandboxServer;
        try {
            sandboxServer = sandboxServerConfigService.selectServer(cId);
        } catch (BizException e) {
            // 语义化业务错误（沙箱不在线/被禁用/容量耗尽等）原样上抛，保留真实 code/message，
            // 不压扁成 9198 依赖服务错误
            throw e;
        } catch (Exception e) {
            log.warn("[GitRpcClient] selectServer failed cId={}", cId, e);
            throw BizException.of(BizExceptionCodeEnum.agentDependencyServiceError);
        }
        if (sandboxServer == null) {
            throw BizException.of(BizExceptionCodeEnum.agentSandboxNotFound);
        }
        return sandboxServer;
    }

    /**
     * 按会话解析项目上下文，写入 file-server 定位参数（serviceType 容器路由 + workspaceType 工作空间类型 +
     * appId/workspacePath，与文件/工作空间接口同一套规则，复用 WorkspaceServiceSupport.applyServiceParams），
     * 返回目标沙箱与项目上下文（同请求只解析一次；ctx 供 doGet/doPost 注入 userApp 路由 header）。
     * userapp 会话缺 appId 等数据异常（BizException）直接抛出，不静默回退 taskAgent 路由：
     * 防止破坏性 git 操作（discard/reset 等）落到错误的 taskAgent 默认工作区。
     */
    private TaskAgentContext applyConversationServiceParamsBody(Map<String, Object> body, Long cId) {
        SandboxServerConfig.SandboxServer server = requireTaskAgentServer(cId);
        WorkspaceServiceContext ctx = WorkspaceServiceSupport.resolveServiceContext(cId, server);
        WorkspaceServiceSupport.applyServiceParams(body, ctx);
        return new TaskAgentContext(server, ctx);
    }

    /** GET query 版（String 值）：委托 body 版解析路由，再字符串化写入 query 参数 */
    private TaskAgentContext applyConversationServiceParams(Map<String, String> params, Long cId) {
        Map<String, Object> contextParams = new HashMap<>();
        TaskAgentContext call = applyConversationServiceParamsBody(contextParams, cId);
        contextParams.forEach((key, value) -> params.put(key, String.valueOf(value)));
        return call;
    }

    // ======================== pageApp URL 解析 ========================

    private PageAppContext resolvePageAppContext(Long projectId) {
        CustomPageDto pageDto = iCustomPageRpcService.queryDetail(projectId);
        if (pageDto == null) {
            throw BizException.of(BizExceptionCodeEnum.customPageProjectConfigNotFound);
        }
        Long spaceId = pageDto.getSpaceId();
        Long sandboxId = pageDto.getSandboxId();
        Long tenantId = pageDto.getTenantId();

        RequestContext<?> requestContext = RequestContext.get();
        Long userId = requestContext == null ? null : requestContext.getUserId();
        if (tenantId == null && requestContext != null) {
            tenantId = requestContext.getTenantId();
        }
        String baseUrl = null;
        String isolationType = null;
        String apiKey = null;

        if (sandboxId != null) {
            SandboxEndpoint endpoint = sandboxConfigEndpoints.resolvePageAppSandbox(tenantId, userId, spaceId, projectId, sandboxId);
            if (endpoint.fileBaseUrl() == null) {
                throw BizException.of(BizExceptionCodeEnum.agentSandboxConfigError);
            }
            baseUrl = endpoint.fileBaseUrl() + "/api";
            isolationType = endpoint.isolationType();
            apiKey = endpoint.apiKey();
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = configuredBaseUrl;
        }

        return new PageAppContext(baseUrl, tenantId, spaceId, isolationType, apiKey);
    }

    // ======================== HTTP 辅助方法 ========================

    // 连接超时收紧（防 file-server 挂起长期占用 HTTP 线程）；读超时放宽——大工作区的 commit/diff
    // 可能长时间无字节下发，SO_TIMEOUT 是单次阻塞读上限而非总时长，流式响应不受影响
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private static final int READ_TIMEOUT_MS = 20 * 60_000;

    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    /** URL 编码一次生成 URI（防 RestTemplate 对 String 二次编码），见 {@link UriEncodingSupport} */
    private static URI buildEncodedUri(UriComponentsBuilder builder) {
        return UriEncodingSupport.buildEncodedUri(builder);
    }

    private Map<String, Object> doGet(TaskAgentContext call, String path, Map<String, String> params) {
        return doGet(getBaseUrl(call.server), path, params, call.serviceContext, call.server.getServerApiKey());
    }

    private Map<String, Object> doGet(PageAppContext ctx, String path, Map<String, String> params) {
        return doGet(ctx.baseUrl, path, params, null, ctx.apiKey);
    }

    /** serviceContext 非空时注入容器路由 header（x-service-type 等；沙箱入口据此转发对应容器）；apiKey 统一注入 x-api-key 鉴权头 */
    private Map<String, Object> doGet(String baseUrl, String path, Map<String, String> params,
                                      WorkspaceServiceContext serviceContext, String apiKey) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + path);
        if (params != null) {
            params.forEach(builder::queryParam);
        }
        URI uri = buildEncodedUri(builder);
        log.info("[git-client] GET url={}", uri);

        HttpHeaders headers = new HttpHeaders();
        if (serviceContext != null) {
            WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext);
        }
        WorkspaceServiceSupport.applySandboxAuthHeader(headers, apiKey);
        RestTemplate restTemplate = createRestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), new ParameterizedTypeReference<>() {
                    });
            return entity.getBody();
        } catch (HttpStatusCodeException e) {
            log.warn("[git-client] GET failed, status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(path, e);
        }
    }

    private Map<String, Object> doPost(TaskAgentContext call, String path, Map<String, Object> body) {
        return doPost(getBaseUrl(call.server), path, body, call.serviceContext, call.server.getServerApiKey());
    }

    private Map<String, Object> doPost(PageAppContext ctx, String path, Map<String, Object> body) {
        return doPost(ctx.baseUrl, path, body, null, ctx.apiKey);
    }

    /** 语义同 GET 版：serviceContext 非空时注入容器路由 header；apiKey 统一注入 x-api-key 鉴权头 */
    private Map<String, Object> doPost(String baseUrl, String path, Map<String, Object> body,
                                       WorkspaceServiceContext serviceContext, String apiKey) {
        String url = baseUrl + path;
        log.info("[git-client] POST url={}", url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (serviceContext != null) {
            WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext);
        }
        WorkspaceServiceSupport.applySandboxAuthHeader(headers, apiKey);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = createRestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(
                    url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<>() {
                    });
            return entity.getBody();
        } catch (HttpStatusCodeException e) {
            log.warn("[git-client] POST failed, status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(path, e);
        }
    }

    private Map<String, Object> parseClientErr(String logId, HttpStatusCodeException e) {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", false);
        resultMap.put("message", e.getMessage());
        try {
            String responseBody = e.getResponseBodyAsString();
            if (responseBody != null && !responseBody.isEmpty()) {
                ObjectMapper objectMapper = new ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, Object> errorResponse = objectMapper.readValue(responseBody, Map.class);
                if (errorResponse.containsKey("code")) {
                    resultMap.put("code", errorResponse.get("code"));
                }
                if (errorResponse.containsKey("message")) {
                    resultMap.put("message", errorResponse.get("message"));
                } else if (errorResponse.containsKey("error")) {
                    Object errorObj = errorResponse.get("error");
                    if (errorObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> errorMap = (Map<String, Object>) errorObj;
                        if (errorMap.containsKey("message")) {
                            resultMap.put("message", errorMap.get("message"));
                        }
                    }
                }
            }
        } catch (Exception parseException) {
            log.error("[git-client] logId={} 解析错误响应体失败", logId, parseException);
        }
        return resultMap;
    }
}
