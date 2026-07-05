package com.xspaceagi.agent.core.infra.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.custompage.sdk.ICustomPageRpcService;
import com.xspaceagi.custompage.sdk.dto.CustomPageDto;
import com.xspaceagi.sandbox.sdk.server.ISandboxConfigRpcService;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigValue;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

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
    private ISandboxConfigRpcService iSandboxConfigRpcService;
    @Resource
    private ICustomPageRpcService iCustomPageRpcService;

    // ======================== 1. init ========================

    public Map<String, Object> initTaskAgent(Long cId, Long userId) {
        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", "taskAgent");
        params.put("cId", String.valueOf(cId));
        params.put("userId", String.valueOf(userId));
        return doGet(getBaseUrlForTaskAgent(cId), "/git/init", params);
    }

    public Map<String, Object> initPageApp(Long projectId) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", "pageApp");
        params.put("projectId", String.valueOf(projectId));
        params.put("tenantId", String.valueOf(ctx.tenantId));
        params.put("spaceId", String.valueOf(ctx.spaceId));
        if (ctx.isolationType != null) {
            params.put("isolationType", ctx.isolationType);
        }
        return doGet(ctx.baseUrl, "/git/init", params);
    }

    // ======================== 2. status ========================

    public Map<String, Object> statusTaskAgent(Long cId, Long userId) {
        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", "taskAgent");
        params.put("cId", String.valueOf(cId));
        params.put("userId", String.valueOf(userId));
        return doGet(getBaseUrlForTaskAgent(cId), "/git/status", params);
    }

    public Map<String, Object> statusPageApp(Long projectId) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", "pageApp");
        params.put("projectId", String.valueOf(projectId));
        params.put("tenantId", String.valueOf(ctx.tenantId));
        params.put("spaceId", String.valueOf(ctx.spaceId));
        if (ctx.isolationType != null) {
            params.put("isolationType", ctx.isolationType);
        }
        return doGet(ctx.baseUrl, "/git/status", params);
    }

    // ======================== 3. commit ========================

    public Map<String, Object> commitTaskAgent(Long cId, Long userId, String message,
                                               List<String> files, String authorName, String authorEmail) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "taskAgent");
        body.put("cId", cId);
        body.put("userId", userId);
        body.put("message", message);
        body.put("files", files);
        body.put("authorName", authorName);
        body.put("authorEmail", authorEmail);
        return doPost(getBaseUrlForTaskAgent(cId), "/git/commit", body);
    }

    public Map<String, Object> commitPageApp(Long projectId, String message,
                                             List<String> files, String authorName, String authorEmail) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "pageApp");
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
        return doPost(ctx.baseUrl, "/git/commit", body);
    }

    // ======================== 4. add ========================

    public Map<String, Object> addTaskAgent(Long cId, Long userId, List<String> files) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "taskAgent");
        body.put("cId", cId);
        body.put("userId", userId);
        body.put("files", files);
        return doPost(getBaseUrlForTaskAgent(cId), "/git/add", body);
    }

    public Map<String, Object> addPageApp(Long projectId, List<String> files) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "pageApp");
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("files", files);
        return doPost(ctx.baseUrl, "/git/add", body);
    }

    // ======================== 5. unstage ========================

    public Map<String, Object> unstageTaskAgent(Long cId, Long userId, List<String> files) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "taskAgent");
        body.put("cId", cId);
        body.put("userId", userId);
        body.put("files", files);
        return doPost(getBaseUrlForTaskAgent(cId), "/git/unstage", body);
    }

    public Map<String, Object> unstagePageApp(Long projectId, List<String> files) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "pageApp");
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("files", files);
        return doPost(ctx.baseUrl, "/git/unstage", body);
    }

    // ======================== 5. discard ========================

    public Map<String, Object> discardTaskAgent(Long cId, Long userId, List<String> files) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "taskAgent");
        body.put("cId", cId);
        body.put("userId", userId);
        body.put("files", files);
        return doPost(getBaseUrlForTaskAgent(cId), "/git/discard", body);
    }

    public Map<String, Object> discardPageApp(Long projectId, List<String> files) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "pageApp");
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("files", files);
        return doPost(ctx.baseUrl, "/git/discard", body);
    }

    // ======================== 6. log ========================

    public Map<String, Object> logTaskAgent(Long cId, Long userId, Integer page, Integer pageSize, String branch, String filePath) {
        int ps = (pageSize != null && pageSize > 0) ? pageSize : 50;
        int pg = (page != null && page > 0) ? page : 1;
        int skip = (pg - 1) * ps;

        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", "taskAgent");
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
        return doGet(getBaseUrlForTaskAgent(cId), "/git/log", params);
    }

    public Map<String, Object> logPageApp(Long projectId, Integer page, Integer pageSize, String branch, String filePath) {
        int ps = (pageSize != null && pageSize > 0) ? pageSize : 50;
        int pg = (page != null && page > 0) ? page : 1;
        int skip = (pg - 1) * ps;

        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", "pageApp");
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
        return doGet(ctx.baseUrl, "/git/log", params);
    }

    // ======================== 7. diff ========================

    public Map<String, Object> diffTaskAgent(Long cId, Long userId, String source, String from, String to, List<String> paths) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "taskAgent");
        body.put("cId", cId);
        body.put("userId", userId);
        body.put("source", source);
        body.put("from", from);
        body.put("to", to);
        body.put("paths", paths);
        return doPost(getBaseUrlForTaskAgent(cId), "/git/diff", body);
    }

    public Map<String, Object> diffPageApp(Long projectId, String source, String from, String to, List<String> paths) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "pageApp");
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
        return doPost(ctx.baseUrl, "/git/diff", body);
    }

    // ======================== 7.5 file-content ========================

    public Map<String, Object> fileContentTaskAgent(Long cId, Long userId, String ref, String filePath) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "taskAgent");
        body.put("cId", cId);
        body.put("userId", userId);
        body.put("ref", ref);
        body.put("filePath", filePath);
        return doPost(getBaseUrlForTaskAgent(cId), "/git/file-content", body);
    }

    public Map<String, Object> fileContentPageApp(Long projectId, String ref, String filePath) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "pageApp");
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("ref", ref);
        body.put("filePath", filePath);
        return doPost(ctx.baseUrl, "/git/file-content", body);
    }

    // ======================== 8. reset ========================

    public Map<String, Object> resetTaskAgent(Long cId, Long userId, String target, String mode) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "taskAgent");
        body.put("cId", cId);
        body.put("userId", userId);
        body.put("target", target);
        body.put("mode", mode);
        return doPost(getBaseUrlForTaskAgent(cId), "/git/reset", body);
    }

    public Map<String, Object> resetPageApp(Long projectId, String target, String mode) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "pageApp");
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("target", target);
        body.put("mode", mode);
        return doPost(ctx.baseUrl, "/git/reset", body);
    }

    // ======================== 9. checkout ========================

    public Map<String, Object> checkoutTaskAgent(Long cId, Long userId, String target) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "taskAgent");
        body.put("cId", cId);
        body.put("userId", userId);
        body.put("target", target);
        return doPost(getBaseUrlForTaskAgent(cId), "/git/checkout", body);
    }

    public Map<String, Object> checkoutPageApp(Long projectId, String target) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "pageApp");
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("target", target);
        return doPost(ctx.baseUrl, "/git/checkout", body);
    }

    // ======================== 10. revert ========================

    public Map<String, Object> revertTaskAgent(Long cId, Long userId, String target, String message,
                                                String authorName, String authorEmail) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "taskAgent");
        body.put("cId", cId);
        body.put("userId", userId);
        body.put("target", target);
        body.put("message", message);
        body.put("authorName", authorName);
        body.put("authorEmail", authorEmail);
        return doPost(getBaseUrlForTaskAgent(cId), "/git/revert", body);
    }

    public Map<String, Object> revertPageApp(Long projectId, String target, String message,
                                              String authorName, String authorEmail) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "pageApp");
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
        return doPost(ctx.baseUrl, "/git/revert", body);
    }

    // ======================== 11. tags ========================

    public Map<String, Object> tagsTaskAgent(Long cId, Long userId) {
        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", "taskAgent");
        params.put("cId", String.valueOf(cId));
        params.put("userId", String.valueOf(userId));
        return doGet(getBaseUrlForTaskAgent(cId), "/git/tags", params);
    }

    public Map<String, Object> tagsPageApp(Long projectId) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", "pageApp");
        params.put("projectId", String.valueOf(projectId));
        params.put("tenantId", String.valueOf(ctx.tenantId));
        params.put("spaceId", String.valueOf(ctx.spaceId));
        if (ctx.isolationType != null) {
            params.put("isolationType", ctx.isolationType);
        }
        return doGet(ctx.baseUrl, "/git/tags", params);
    }

    // ======================== 12. tag-create ========================

    public Map<String, Object> tagCreateTaskAgent(Long cId, Long userId, String tagName, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "taskAgent");
        body.put("cId", cId);
        body.put("userId", userId);
        body.put("tagName", tagName);
        body.put("message", message);
        return doPost(getBaseUrlForTaskAgent(cId), "/git/tag-create", body);
    }

    public Map<String, Object> tagCreatePageApp(Long projectId, String tagName, String message) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "pageApp");
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("tagName", tagName);
        body.put("message", message);
        return doPost(ctx.baseUrl, "/git/tag-create", body);
    }

    // ======================== 13. tag-delete ========================

    public Map<String, Object> tagDeleteTaskAgent(Long cId, Long userId, String tagName) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "taskAgent");
        body.put("cId", cId);
        body.put("userId", userId);
        body.put("tagName", tagName);
        return doPost(getBaseUrlForTaskAgent(cId), "/git/tag-delete", body);
    }

    public Map<String, Object> tagDeletePageApp(Long projectId, String tagName) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "pageApp");
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("tagName", tagName);
        return doPost(ctx.baseUrl, "/git/tag-delete", body);
    }

    // ======================== 14. branches ========================

    public Map<String, Object> branchesTaskAgent(Long cId, Long userId) {
        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", "taskAgent");
        params.put("cId", String.valueOf(cId));
        params.put("userId", String.valueOf(userId));
        return doGet(getBaseUrlForTaskAgent(cId), "/git/branches", params);
    }

    public Map<String, Object> branchesPageApp(Long projectId) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, String> params = new HashMap<>();
        params.put("workspaceType", "pageApp");
        params.put("projectId", String.valueOf(projectId));
        params.put("tenantId", String.valueOf(ctx.tenantId));
        params.put("spaceId", String.valueOf(ctx.spaceId));
        if (ctx.isolationType != null) {
            params.put("isolationType", ctx.isolationType);
        }
        return doGet(ctx.baseUrl, "/git/branches", params);
    }

    // ======================== 15. branch-create ========================

    public Map<String, Object> branchCreateTaskAgent(Long cId, Long userId, String branchName, String startPoint) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "taskAgent");
        body.put("cId", cId);
        body.put("userId", userId);
        body.put("branchName", branchName);
        body.put("startPoint", startPoint);
        return doPost(getBaseUrlForTaskAgent(cId), "/git/branch-create", body);
    }

    public Map<String, Object> branchCreatePageApp(Long projectId, String branchName, String startPoint) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "pageApp");
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("branchName", branchName);
        body.put("startPoint", startPoint);
        return doPost(ctx.baseUrl, "/git/branch-create", body);
    }

    // ======================== 16. branch-switch ========================

    public Map<String, Object> branchSwitchTaskAgent(Long cId, Long userId, String branchName) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "taskAgent");
        body.put("cId", cId);
        body.put("userId", userId);
        body.put("branchName", branchName);
        return doPost(getBaseUrlForTaskAgent(cId), "/git/branch-switch", body);
    }

    public Map<String, Object> branchSwitchPageApp(Long projectId, String branchName) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "pageApp");
        body.put("projectId", projectId);
        body.put("tenantId", ctx.tenantId);
        body.put("spaceId", ctx.spaceId);
        if (ctx.isolationType != null) {
            body.put("isolationType", ctx.isolationType);
        }
        body.put("branchName", branchName);
        return doPost(ctx.baseUrl, "/git/branch-switch", body);
    }

    // ======================== 17. branch-delete ========================

    public Map<String, Object> branchDeleteTaskAgent(Long cId, Long userId, String branchName, Boolean force) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "taskAgent");
        body.put("cId", cId);
        body.put("userId", userId);
        body.put("branchName", branchName);
        if (force != null) {
            body.put("force", force);
        }
        return doPost(getBaseUrlForTaskAgent(cId), "/git/branch-delete", body);
    }

    public Map<String, Object> branchDeletePageApp(Long projectId, String branchName, Boolean force) {
        PageAppContext ctx = resolvePageAppContext(projectId);
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "pageApp");
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
        return doPost(ctx.baseUrl, "/git/branch-delete", body);
    }

    // ======================== 内部类 ========================

    private static class PageAppContext {
        final String baseUrl;
        final Long tenantId;
        final Long spaceId;
        final String isolationType;

        PageAppContext(String baseUrl, Long tenantId, Long spaceId, String isolationType) {
            this.baseUrl = baseUrl;
            this.tenantId = tenantId;
            this.spaceId = spaceId;
            this.isolationType = isolationType;
        }
    }

    // ======================== taskAgent URL 解析 ========================

    private String getBaseUrlForTaskAgent(Long cId) {
        SandboxServerConfig.SandboxServer sandboxServer;
        try {
            sandboxServer = sandboxServerConfigService.selectServer(cId);
        } catch (Exception e) {
            log.warn("[GitRpcClient] selectServer failed cId={}", cId, e);
            throw BizException.of(BizExceptionCodeEnum.agentDependencyServiceError);
        }
        if (sandboxServer == null) {
            throw BizException.of(BizExceptionCodeEnum.agentSandboxNotFound);
        }
        return sandboxServer.getServerFileUrl() + "/api";
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

        if (sandboxId != null) {
            SandboxConfigRpcDto sandboxConfig = iSandboxConfigRpcService.selectAppDevelopmentSandbox(tenantId, userId, spaceId, projectId, sandboxId);
            if (sandboxConfig == null || sandboxConfig.getConfigValue() == null) {
                throw BizException.of(BizExceptionCodeEnum.agentSandboxNotFound);
            }
            SandboxConfigValue configValue = sandboxConfig.getConfigValue();
            if (configValue.getHostWithScheme() == null || configValue.getHostWithScheme().isBlank()
                    || configValue.getFileServerPort() <= 0) {
                throw BizException.of(BizExceptionCodeEnum.agentSandboxConfigError);
            }
            baseUrl = configValue.getHostWithScheme() + ":" + configValue.getFileServerPort() + "/api";
            isolationType = sandboxConfig.getIsolation() == null ? null : sandboxConfig.getIsolation().name();
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = configuredBaseUrl;
        }

        return new PageAppContext(baseUrl, tenantId, spaceId, isolationType);
    }

    // ======================== HTTP 辅助方法 ========================

    private Map<String, Object> doGet(String baseUrl, String path, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + path);
        if (params != null) {
            params.forEach(builder::queryParam);
        }
        String url = builder.toUriString();
        log.info("[git-client] GET url={}", url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(
                    url, HttpMethod.GET, null, new ParameterizedTypeReference<>() {
                    });
            return entity.getBody();
        } catch (HttpClientErrorException e) {
            log.warn("[git-client] GET failed, status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(path, e);
        }
    }

    private Map<String, Object> doPost(String baseUrl, String path, Map<String, Object> body) {
        String url = baseUrl + path;
        log.info("[git-client] POST url={}", url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(
                    url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<>() {
                    });
            return entity.getBody();
        } catch (HttpClientErrorException e) {
            log.warn("[git-client] POST failed, status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(path, e);
        }
    }

    private Map<String, Object> parseClientErr(String logId, HttpClientErrorException e) {
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
