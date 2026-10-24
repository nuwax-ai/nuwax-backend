package com.xspaceagi.custompage.domain.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.custompage.domain.dto.PageFileInfo;
import com.xspaceagi.custompage.domain.model.CustomPageConfigModel;
import com.xspaceagi.custompage.domain.repository.ICustomPageConfigRepository;
import com.xspaceagi.custompage.domain.util.UriEncodingUtil;
import com.xspaceagi.custompage.sdk.dto.ProjectConfigExportDto;
import com.xspaceagi.custompage.sdk.dto.TemplateTypeEnum;
import com.xspaceagi.sandbox.sdk.server.ISandboxConfigRpcService;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigValue;
import com.xspaceagi.system.spec.common.RequestContext;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.*;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.retry.Retry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class PageAppFileClient {

    @Value("${custom-page.build-server.base-url:}")
    private String configuredBaseUrl;
    @Resource
    private ICustomPageConfigRepository customPageConfigRepository;
    @Resource
    private ISandboxConfigRpcService sandboxConfigRpcService;

    // 空闲连接淘汰：默认连接池永不淘汰空闲连接，而下游（沙箱 file-server）连接会随沙箱回收/中间层被重置，
    // 复用死连接表现为 "recvAddress failed: 连接被对方重设"。60s 淘汰 + 后台定期清理规避。
    private static final Duration POOL_MAX_IDLE_TIME = Duration.ofSeconds(60);
    private static final Duration POOL_MAX_LIFE_TIME = Duration.ofMinutes(5);
    private static final Duration POOL_EVICTION_INTERVAL = Duration.ofSeconds(30);
    // 响应读空闲超时（相邻两次网络读之间的最大间隔，非响应总时长）：仅防挂起，持续有数据的流式传输不触发。
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);

    private final WebClient webClient = createWebClient();

    /** 显式配置连接池空闲淘汰与读空闲超时，避免复用已被对端重置的连接 */
    private static WebClient createWebClient() {
        ConnectionProvider provider = ConnectionProvider.builder("page-app-file")
                .maxConnections(500)
                .maxIdleTime(POOL_MAX_IDLE_TIME)
                .maxLifeTime(POOL_MAX_LIFE_TIME)
                .evictInBackground(POOL_EVICTION_INTERVAL)
                .build();
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create(provider).responseTimeout(RESPONSE_TIMEOUT)))
                .build();
    }

    /** 传输层连接异常判定（遍历 cause 链）：连接被对端重置/提前关闭等（NativeIoException、PrematureCloseException 均为 IOException 子类）；HTTP 业务响应错误不属于此类 */
    private static boolean isConnectionError(Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    public Map<String, Object> startDev(Long projectId, String devProxyPath) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        URI url = UriEncodingUtil.buildEncodedUri(appendRuntimeParamsToQuery(
                UriComponentsBuilder.fromHttpUrl(buildBaseUrl(ctx) + "/build/start-dev")
                        .queryParam("projectId", projectId)
                        .queryParam("basePath", devProxyPath),
                ctx));
        log.info("[file-client] project Id={} call dev start API, url={}", projectId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, sandboxAuthEntity(ctx), new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[file-client] project Id={} call dev start API, response={}", projectId, body);
            return body;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} call dev start APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> keepAlive(Long projectId, String devProxyPath, Integer devPid, Integer devPort) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        URI url = UriEncodingUtil.buildEncodedUri(appendRuntimeParamsToQuery(
                UriComponentsBuilder.fromHttpUrl(buildBaseUrl(ctx) + "/build/keep-alive")
                        .queryParam("projectId", projectId)
                        .queryParam("basePath", devProxyPath)
                        .queryParam("pid", devPid)
                        .queryParam("port", devPort),
                ctx));
        log.info("[file-client] project Id={} callkeep-alive API, url={}", projectId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, sandboxAuthEntity(ctx), new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[file-client] project Id={} callkeep-alive API, response={}", projectId, body);
            return body;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} callkeep-alive APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> build(Long projectId, String prodProxyPath) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        URI url = UriEncodingUtil.buildEncodedUri(appendRuntimeParamsToQuery(
                UriComponentsBuilder.fromHttpUrl(buildBaseUrl(ctx) + "/build/build")
                        .queryParam("projectId", projectId)
                        .queryParam("basePath", prodProxyPath),
                ctx));
        log.info("[file-client] project Id={} callbuild API, url={}", projectId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, sandboxAuthEntity(ctx), new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[file-client] project Id={} callbuild API, response={}", projectId, body);
            return body;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} callbuild APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> stopDev(Long projectId, Integer pid) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        URI url = UriEncodingUtil.buildEncodedUri(appendRuntimeParamsToQuery(
                UriComponentsBuilder.fromHttpUrl(buildBaseUrl(ctx) + "/build/stop-dev")
                        .queryParam("projectId", projectId)
                        .queryParam("pid", pid),
                ctx));
        log.info("[file-client] project Id={} callstop dev server API, url={}", projectId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, sandboxAuthEntity(ctx), new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[file-client] project Id={} callstop dev server API, response={}", projectId, body);
            return body;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} callstop dev server APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> restartDev(Long projectId, Integer pid, String devProxyPath) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        UriComponentsBuilder uriComponentsBuilder = UriComponentsBuilder.fromHttpUrl(buildBaseUrl(ctx) + "/build/restart-dev").queryParam("projectId", projectId).queryParam("basePath", devProxyPath);

        if (pid != null) {
            uriComponentsBuilder.queryParam("pid", pid);
        }
        URI url = UriEncodingUtil.buildEncodedUri(appendRuntimeParamsToQuery(uriComponentsBuilder, ctx));
        log.info("[file-client] project Id={} callrestart dev server API, url={}", projectId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, sandboxAuthEntity(ctx), new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[file-client] project Id={} callrestart dev server API, response={}", projectId, body);
            return body;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} callrestart dev server APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> createProject(Long projectId, TemplateTypeEnum templateType) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        String url = buildBaseUrl(ctx) + "/project/create-project";
        log.info("[file-client] project Id={} callcreate-project API, url={}", projectId, url);

        // 创建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("projectId", String.valueOf(projectId));
        requestBody.put("templateType", TemplateTypeEnum.defaultType(templateType).getValue());

        // 设置请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        applySandboxAuthHeader(headers, ctx);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(
                appendRuntimeParamsToBody(requestBody, ctx), headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[file-client] project Id={} callcreate-project API, response={}", projectId, body);
            return body;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} callcreate-project APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> uploadProject(Long projectId, MultipartFile file, Integer codeVersion, Integer pid, String devProxyPath) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        String url = buildBaseUrl(ctx) + "/project/upload-project";
        log.info("[file-client] project Id={} callupload project API, url={}, code Version={}", projectId, url, codeVersion);

        // 创建请求体，包含文件、projectId和版本号
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        applySandboxAuthHeader(headers, ctx);

        // 创建MultiValueMap来存储文件、projectId和版本号
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file.getResource());
        body.add("projectId", String.valueOf(projectId));
        body.add("codeVersion", String.valueOf(codeVersion));
        body.add("pid", pid);
        if (devProxyPath != null) {
            body.add("basePath", devProxyPath);
        }
        appendRuntimeParamsToBody(body, ctx);

        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[file-client] project Id={} callupload project API, response={}", projectId, responseBody);
            return responseBody;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} callupload project APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> getProjectContent(Long projectId, String command, String proxyPath) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        URI url = UriEncodingUtil.buildEncodedUri(appendRuntimeParamsToQuery(
                UriComponentsBuilder.fromHttpUrl(buildBaseUrl(ctx) + "/project/get-project-content")
                        .queryParam("projectId", projectId)
                        .queryParam("command", command)
                        .queryParam("proxyPath", proxyPath),
                ctx));
        log.info("[file-client] project Id={} callqueryprojectcontent API, url={}", projectId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, sandboxAuthEntity(ctx), new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[file-client] project Id={} callqueryprojectcontent API, response sent", projectId);
            return body;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} callqueryprojectcontent APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> getProjectContentByVersion(Long projectId, Integer codeVersion, String command, String proxyPath) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        URI url = UriEncodingUtil.buildEncodedUri(appendRuntimeParamsToQuery(
                UriComponentsBuilder.fromHttpUrl(buildBaseUrl(ctx) + "/project/get-project-content-by-version")
                        .queryParam("projectId", projectId)
                        .queryParam("codeVersion", codeVersion)
                        .queryParam("command", command)
                        .queryParam("proxyPath", proxyPath),
                ctx));
        log.info("[file-client] project Id={} callqueryprojecthistorical version content API, url={}", projectId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, sandboxAuthEntity(ctx), new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[file-client] project Id={} callqueryprojecthistorical version content API, response sent", projectId);
            return body;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} callqueryprojecthistorical version content APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    /**
     * codeVersion 是当前版本，上传后版本会+1
     */
    public Map<String, Object> specifiedFilesUpdate(Long projectId, List<PageFileInfo> files, Integer codeVersion, String devProxyPath, Integer devPid) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        String url = buildBaseUrl(ctx) + "/project/specified-files-update";
        log.info("[file-client] project Id={} specifiedfileupdate, url={}", projectId, url);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("projectId", String.valueOf(projectId));
        requestBody.put("files", files);
        requestBody.put("codeVersion", codeVersion);
        requestBody.put("basePath", devProxyPath);
        requestBody.put("pid", devPid);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        applySandboxAuthHeader(headers, ctx);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(
                appendRuntimeParamsToBody(requestBody, ctx), headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[file-client] project Id={} specifiedfileupdate, response={}", projectId, body);
            return body;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} callspecifiedfileupdate APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    /**
     * codeVersion 是当前版本，上传后版本会+1
     */
    public Map<String, Object> allFilesUpdate(Long projectId, List<PageFileInfo> files, Integer codeVersion, String devProxyPath, Integer devPid) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        String url = buildBaseUrl(ctx) + "/project/all-files-update";
        log.info("[file-client] project Id={} fullfileupdate, url={}", projectId, url);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("projectId", String.valueOf(projectId));
        requestBody.put("files", files);
        requestBody.put("codeVersion", codeVersion);
        requestBody.put("basePath", devProxyPath);
        requestBody.put("pid", devPid);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        applySandboxAuthHeader(headers, ctx);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(
                appendRuntimeParamsToBody(requestBody, ctx), headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[file-client] project Id={} fullfileupdate, response={}", projectId, body);
            return body;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} callfullfileupdate APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    /**
     * 批量上传文件
     * codeVersion 是当前版本，上传后版本会+1
     */
    public Map<String, Object> uploadBatchFiles(Long projectId, List<MultipartFile> files, List<String> filePaths, Integer codeVersion) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        String url = buildBaseUrl(ctx) + "/project/upload-batch-files";
        log.info("[file-client] project Id={} upload batch files, url={}, fileCount={}, code Version={}", projectId, url, files.size(), codeVersion);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        applySandboxAuthHeader(headers, ctx);

        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        for (MultipartFile file : files) {
            body.add("files", file.getResource());
        }
        body.add("projectId", String.valueOf(projectId));
        body.add("codeVersion", String.valueOf(codeVersion));
        for (String fp : filePaths) {
            body.add("filePaths", fp);
        }
        appendRuntimeParamsToBody(body, ctx);

        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[file-client] project Id={} upload batch files, response={}", projectId, responseBody);
            return responseBody;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} call upload batch files API failed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    /**
     * codeVersion 是当前版本，上传后版本会+1
     */
    public Map<String, Object> uploadSingleFile(Long projectId, MultipartFile file, String filePath, Integer codeVersion) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        String url = buildBaseUrl(ctx) + "/project/upload-single-file";
        log.info("[file-client] project Id={} upload single file, url={}, file Path={}, code Version={}", projectId, url, filePath, codeVersion);

        // 创建请求体，包含文件、projectId、filePath和codeVersion
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        applySandboxAuthHeader(headers, ctx);

        // 创建MultiValueMap来存储文件和其他参数
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file.getResource());
        body.add("projectId", String.valueOf(projectId));
        body.add("filePath", filePath);
        body.add("codeVersion", String.valueOf(codeVersion));
        appendRuntimeParamsToBody(body, ctx);

        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[file-client] project Id={} upload single file, response={}", projectId, responseBody);
            return responseBody;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} callupload single file APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> uploadAttachmentFile(Long projectId, MultipartFile file, String uploadFileName) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        String url = buildBaseUrl(ctx) + "/project/upload-attachment-file";
        log.info("[file-client] project Id={} upload attachment, url={}, upload File Name={}", projectId, url, uploadFileName);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        applySandboxAuthHeader(headers, ctx);

        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file.getResource());
        body.add("projectId", String.valueOf(projectId));
        body.add("fileName", uploadFileName);
        appendRuntimeParamsToBody(body, ctx);

        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[file-client] project Id={} upload attachment, response={}", projectId, responseBody);
            return responseBody;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} callupload attachment APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> pushSkillsToWorkspace(Long projectId, MultipartFile zipFile, List<String> skillUrls) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        String url = buildBaseUrl(ctx) + "/project/push-skills-to-workspace";
        log.info("[file-client] project Id={} push skills to workspace, url={}", projectId, url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        applySandboxAuthHeader(headers, ctx);

        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("projectId", String.valueOf(projectId));
        if (zipFile != null) {
            body.add("file", zipFile.getResource());
        }
        if (skillUrls != null && !skillUrls.isEmpty()) {
            body.add("skillUrls", skillUrls);
        }
        appendRuntimeParamsToBody(body, ctx);

        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[file-client] project Id={} push skills to workspace, response={}", projectId, responseBody);
            return responseBody;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} push skills to workspace failed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> backupCurrentVersion(Long projectId, Integer codeVersion) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        String url = buildBaseUrl(ctx) + "/project/backup-current-version";
        log.info("[file-client] project Id={} backup current version, url={}, code Version={}", projectId, url, codeVersion);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("projectId", String.valueOf(projectId));
        requestBody.put("codeVersion", codeVersion);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        applySandboxAuthHeader(headers, ctx);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(
                appendRuntimeParamsToBody(requestBody, ctx), headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[file-client] project Id={} backup current version, response={}", projectId, body);
            return body;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} callbackup current version APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    /**
     * codeVersion 是当前版本，回滚后版本会+1
     */
    public Map<String, Object> rollbackVersion(Long projectId, Integer rollbackTo, Integer codeVersion, String devProxyPath, Integer devPid) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        String url = buildBaseUrl(ctx) + "/project/rollback-version";
        log.info("[file-client] project Id={} rollback version, url={}, rollback To={}, code Version={}", projectId, url, rollbackTo, codeVersion);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("projectId", String.valueOf(projectId));
        requestBody.put("rollbackTo", rollbackTo);
        requestBody.put("codeVersion", codeVersion);
        requestBody.put("basePath", devProxyPath);
        requestBody.put("pid", devPid);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        applySandboxAuthHeader(headers, ctx);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(
                appendRuntimeParamsToBody(requestBody, ctx), headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[file-client] project Id={} rollback version, response={}", projectId, body);
            return body;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} callrollback version APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public InputStream exportProject(Long projectId, Integer codeVersion, String exportType, ProjectConfigExportDto configExportDto) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        String url = buildBaseUrl(ctx) + "/project/export-project";
        log.info("[file-client] project Id={} exportproject, url={}, code Version={}, export Type={}", projectId, url, codeVersion, exportType);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("projectId", String.valueOf(projectId));
        requestBody.put("codeVersion", codeVersion);
        requestBody.put("exportType", exportType);
        requestBody.put("config", configExportDto);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        applySandboxAuthHeader(headers, ctx);

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(
                appendRuntimeParamsToBody(requestBody, ctx), headers);

        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<byte[]> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, byte[].class);

        byte[] body = entity.getBody();
        log.info("[file-client] project Id={} exportproject, response size={} bytes", projectId, body != null ? body.length : 0);
        return body != null ? new ByteArrayInputStream(body) : null;
    }

    public Map<String, Object> deleteProject(Long projectId, Integer pid) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        UriComponentsBuilder uriComponentsBuilder = UriComponentsBuilder.fromHttpUrl(buildBaseUrl(ctx) + "/project/delete-project").queryParam("projectId", projectId).queryParam("pid", pid);

        URI url = UriEncodingUtil.buildEncodedUri(appendRuntimeParamsToQuery(uriComponentsBuilder, ctx));
        log.info("[file-client] project Id={} calldelete project API, url={}", projectId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, sandboxAuthEntity(ctx), new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[file-client] project Id={} calldelete project API, response={}", projectId, body);
            return body;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} calldelete project APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> getDevLog(Long projectId, Integer startIndex, String logType) {
        RuntimeContext ctx = getRuntimeContext(projectId);
        URI url = UriEncodingUtil.buildEncodedUri(appendRuntimeParamsToQuery(
                UriComponentsBuilder.fromHttpUrl(buildBaseUrl(ctx) + "/build/get-dev-log")
                        .queryParam("projectId", projectId)
                        .queryParam("startIndex", startIndex)
                        .queryParam("logType", logType),
                ctx));
        log.debug("[file-client] project Id={} callquery logs API, url={}", projectId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, sandboxAuthEntity(ctx), new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.debug("[file-client] project Id={} callquery logs API, response sent", projectId);
            return body;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} query logs API failed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> copyProject(Long sourceProjectId, Long targetProjectId) {
        RuntimeContext sourceRuntimeContext = getRuntimeContext(sourceProjectId);
        RuntimeContext targetRuntimeContext = getRuntimeContext(targetProjectId);
        String url = UriComponentsBuilder.fromHttpUrl(buildBaseUrl(targetRuntimeContext) + "/project/copy-project")
                //.queryParam("sourceProjectId", sourceProjectId)
                //.queryParam("targetProjectId", targetProjectId)
                .toUriString();
        log.info("[file-client] source Project Id={},target Project Id={}, callcopyproject API, url={}", sourceProjectId, targetProjectId, url);
        CustomPageConfigModel sourceConfig = customPageConfigRepository.getById(sourceProjectId);
        CustomPageConfigModel targetConfig = customPageConfigRepository.getById(targetProjectId);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("sourceProjectId", String.valueOf(sourceProjectId));
        requestBody.put("sourceTenantId", sourceConfig == null ? null : sourceConfig.getTenantId());
        requestBody.put("sourceSpaceId", sourceConfig == null ? null : sourceConfig.getSpaceId());
        requestBody.put("sourceIsolationType", sourceRuntimeContext == null ? null : sourceRuntimeContext.getIsolationType());
        requestBody.put("targetProjectId", targetProjectId);
        requestBody.put("targetTenantId", targetConfig == null ? null : targetConfig.getTenantId());
        requestBody.put("targetSpaceId", targetConfig == null ? null : targetConfig.getSpaceId());
        requestBody.put("targetIsolationType", targetRuntimeContext == null ? null : targetRuntimeContext.getIsolationType());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        applySandboxAuthHeader(headers, targetRuntimeContext);

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[file-client] source Project Id={},target Project Id={}, callcopyproject API, response={}", sourceProjectId, targetProjectId, body);
            return body;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] project Id={} query logs API failed, status={}, response Body={}", sourceProjectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(sourceProjectId, e);
        }
    }

    public Map<String, Object> getLogCacheStats() {
        URI url = UriEncodingUtil.buildEncodedUri(appendRuntimeParamsToQuery(
                UriComponentsBuilder.fromHttpUrl(buildBaseUrl(null) + "/build/get-log-cache-stats"), null));
        log.info("[file-client] callgetlogcachestats API, url={}", url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, sandboxAuthEntity(null), new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[file-client] callgetlogcachestats API, response={}", body);
            return body;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] callgetlogcachestats APIfailed, status={}, response Body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(null, e);
        }
    }

    /**
     * 获取静态文件（流式返回）
     */
    public Flux<DataBuffer> getStaticFile(String targetPrefix, String relativePath, String logId) {
        // 使用 UriComponentsBuilder 来正确处理路径编码
        String[] prefixSegments = Arrays.stream(targetPrefix.split("/")).filter(segment -> !segment.isEmpty()).toArray(String[]::new);
        String[] relativeSegments = Arrays.stream(relativePath.split("/")).filter(segment -> !segment.isEmpty()).toArray(String[]::new);

        URI url = UriEncodingUtil.buildEncodedUri(appendRuntimeParamsToQuery(
                UriComponentsBuilder.fromHttpUrl(buildBaseUrl(null)).pathSegment(prefixSegments).pathSegment(relativeSegments),
                null));
        log.info("[file-client] log Id={} callgetstaticfile API, url={}, target Prefix={}, relative Path={}", logId, url, targetPrefix, relativePath);

        // 已发出数据则不重试，避免重放整个响应体造成内容重复拼接
        AtomicBoolean emitted = new AtomicBoolean(false);
        return webClient.get()
                .uri(url)
                .headers(headers -> applySandboxAuthHeader(headers, null))
                .accept(MediaType.ALL) // 接受所有媒体类型，因为静态文件可能是图片、文本等
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .doOnNext(dataBuffer -> emitted.set(true))
                .doOnError(WebClientResponseException.class, e -> {
                    log.warn("[file-client] log Id={} callgetstaticfile APIfailed, status={}, response Body={}", logId, e.getStatusCode(), e.getResponseBodyAsString());
                }).doOnError(Throwable.class, e -> {
                    log.error("[file-client] log Id={} callgetstaticfile APIexception", logId, e);
                }).doOnDiscard(DataBuffer.class, DataBufferUtils::release).doOnComplete(() -> {
                    log.info("[file-client] log Id={} callgetstaticfile API, streaming completed", logId);
                })
                // 复用到被对端重置的连接（沙箱回收/长空闲后被中间层断开）时换新连接重试一次
                .retryWhen(Retry.max(1).filter(e -> !emitted.get() && isConnectionError(e)));
    }

    public Map<String, Object> clearAllLogCache() {
        URI url = UriEncodingUtil.buildEncodedUri(appendRuntimeParamsToQuery(
                UriComponentsBuilder.fromHttpUrl(buildBaseUrl(null) + "/build/clear-all-log-cache"), null));
        log.info("[file-client] callclearlogcache API, url={}", url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, sandboxAuthEntity(null), new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[file-client] callclearlogcache API, response={}", body);
            return body;
        } catch (HttpStatusCodeException e) {
            log.warn("[file-client] callclearlogcache APIfailed, status={}, response Body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(null, e);
        }
    }

    /**
     * 自动 git commit
     */
    public void gitAutoCommit(Long projectId, String message, String authorName, String authorEmail) {
        Map<String, Object> body = new HashMap<>();
        body.put("workspaceType", "pageApp");
        body.put("projectId", projectId);

        RuntimeContext ctx = getRuntimeContext(projectId);
        if (ctx != null) {
            body.put("tenantId", ctx.getTenantId());
            body.put("spaceId", ctx.getSpaceId());
            if (ctx.getIsolationType() != null && !ctx.getIsolationType().isBlank()) {
                body.put("isolationType", ctx.getIsolationType());
            }
        }
        body.put("message", message);
        if (authorName != null) body.put("authorName", authorName);
        if (authorEmail != null) body.put("authorEmail", authorEmail);

        String baseUrl = buildBaseUrl(ctx);
        String url = baseUrl + "/git/commit";
        log.info("[Git] auto commit, project Id={}, url={}", projectId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            applySandboxAuthHeader(headers, ctx);
            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(
                    url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {});
            Map<String, Object> result = entity.getBody();
            log.info("[Git] auto commit result, project Id={}, result={}", projectId, result);
        } catch (HttpStatusCodeException e) {
            log.warn("[Git] auto commit failed, project Id={}, status={}, body={}",
                    projectId, e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("[Git] auto commit failed, project Id={}", projectId, e);
        }
    }

    // 捕获4xx/5xx错误，尝试解析响应体（file-server 的业务错误也带结构化 message，5xx 同样可解析出可用信息）
    private Map<String, Object> parseClientErr(Long projectId, HttpStatusCodeException e) {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", false);
        resultMap.put("message", e.getMessage());
        try {
            String responseBody = e.getResponseBodyAsString();
            if (responseBody != null && !responseBody.isEmpty()) {
                ObjectMapper objectMapper = new ObjectMapper();
                @SuppressWarnings("unchecked") Map<String, Object> errorResponse = objectMapper.readValue(responseBody, Map.class);
                if (errorResponse.containsKey("code")) {
                    // 前端会把 4010 当登录态失效跳转登录页；转 9999 通用错误，message 照常透传
                    Object code = errorResponse.get("code");
                    resultMap.put("code", "4010".equals(String.valueOf(code)) ? "9999" : code);
                }
                if (errorResponse.containsKey("message")) {
                    resultMap.put("message", errorResponse.get("message"));
                } else if (errorResponse.containsKey("error")) {
                    Object errorObj = errorResponse.get("error");
                    if (errorObj instanceof Map) {
                        @SuppressWarnings("unchecked") Map<String, Object> errorMap = (Map<String, Object>) errorObj;
                        if (errorMap.containsKey("message")) {
                            resultMap.put("message", errorMap.get("message"));
                        }
                    }
                }
            }
        } catch (Exception parseException) {
            log.error("[file-client] project Id={} parse error response body failed", projectId, parseException);
        }
        return resultMap;
    }

    /** 下发沙箱通信鉴权头 x-api-key（ctx 为空或无 key 时置空串） */
    private void applySandboxAuthHeader(HttpHeaders headers, RuntimeContext runtimeContext) {
        headers.set("x-api-key", runtimeContext == null || runtimeContext.getApiKey() == null ? "" : runtimeContext.getApiKey());
    }

    /** GET 请求鉴权 entity */
    private HttpEntity<Void> sandboxAuthEntity(RuntimeContext runtimeContext) {
        HttpHeaders headers = new HttpHeaders();
        applySandboxAuthHeader(headers, runtimeContext);
        return new HttpEntity<>(headers);
    }

    private String buildBaseUrl(RuntimeContext runtimeContext) {
        String baseUrl = runtimeContext == null ? null : runtimeContext.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = configuredBaseUrl;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("BaseUrl is required");
        }
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private RuntimeContext getRuntimeContext(Long projectId) {
        String baseUrl = null;
        Long tenantId = null;
        Long spaceId = null;
        String isolationType = null;
        String apiKey = null;
        String podId = null;
        if (projectId != null) {
            try {
                CustomPageConfigModel configModel = customPageConfigRepository.getById(projectId);
                spaceId = configModel == null ? null : configModel.getSpaceId();
                Long sandboxId = configModel == null ? null : configModel.getSandboxId();
                if (sandboxId == null) {
                    log.debug("[file-client] project Id={} sandboxId is null", projectId);
                    return null;
                }
                RequestContext<?> requestContext = RequestContext.get();
                tenantId = requestContext == null ? null : requestContext.getTenantId();
                if (tenantId == null && configModel != null) {
                    tenantId = configModel.getTenantId();
                }
                Long userId = requestContext == null ? null : requestContext.getUserId();
                SandboxConfigRpcDto sandboxConfig = sandboxConfigRpcService.selectAppDevelopmentSandbox(
                        tenantId,
                        userId,
                        configModel.getSpaceId(),
                        projectId,
                        sandboxId);
                if (sandboxConfig != null) {
                    SandboxConfigValue configValue = sandboxConfig.getConfigValue();
                    if (configValue != null && configValue.getHostWithScheme() != null && !configValue.getHostWithScheme().isBlank()
                            && configValue.getFileServerPort() > 0) {
                        baseUrl = configValue.getHostWithScheme() + ":" + configValue.getFileServerPort() + "/api";
                        apiKey = configValue.getApiKey();

                        isolationType = sandboxConfig.getIsolation() == null ? null : sandboxConfig.getIsolation().name();
                        //podId = sandboxConfig.getIsolationKey();
                    }
                }
            } catch (Exception e) {
                log.warn("[file-client] project Id={} resolve dynamic base url failed", projectId, e);
            }
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        return new RuntimeContext(baseUrl, tenantId, spaceId, isolationType, apiKey);
    }

    private UriComponentsBuilder appendRuntimeParamsToQuery(UriComponentsBuilder builder, RuntimeContext runtimeContext) {
        if (runtimeContext == null) {
            return builder;
        }
        if (runtimeContext.getTenantId() != null) {
            builder.queryParam("tenantId", runtimeContext.getTenantId());
        }
        if (runtimeContext.getSpaceId() != null) {
            builder.queryParam("spaceId", runtimeContext.getSpaceId());
        }
        if (runtimeContext.getIsolationType() != null && !runtimeContext.getIsolationType().isBlank()) {
            builder.queryParam("isolationType", runtimeContext.getIsolationType());
        }
        return builder;
    }

    private Map<String, Object> appendRuntimeParamsToBody(Map<String, Object> requestBody, RuntimeContext runtimeContext) {
        if (runtimeContext == null) {
            return requestBody;
        }
        if (runtimeContext.getTenantId() != null) {
            requestBody.put("tenantId", runtimeContext.getTenantId());
        }
        if (runtimeContext.getSpaceId() != null) {
            requestBody.put("spaceId", runtimeContext.getSpaceId());
        }
        if (runtimeContext.getIsolationType() != null && !runtimeContext.getIsolationType().isBlank()) {
            requestBody.put("isolationType", runtimeContext.getIsolationType());
        }
        return requestBody;
    }

    private void appendRuntimeParamsToBody(LinkedMultiValueMap<String, Object> requestBody, RuntimeContext runtimeContext) {
        if (runtimeContext == null) {
            return;
        }
        if (runtimeContext.getTenantId() != null) {
            requestBody.add("tenantId", runtimeContext.getTenantId());
        }
        if (runtimeContext.getSpaceId() != null) {
            requestBody.add("spaceId", runtimeContext.getSpaceId());
        }
        if (runtimeContext.getIsolationType() != null && !runtimeContext.getIsolationType().isBlank()) {
            requestBody.add("isolationType", runtimeContext.getIsolationType());
        }
    }

    @Data
    @AllArgsConstructor
    private static class RuntimeContext {
        private final String baseUrl;
        private final Long tenantId;
        private final Long spaceId;
        private final String isolationType;
        private final String apiKey;
    }

}