package com.xspaceagi.agent.core.infra.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.adapter.dto.ComputerFileInfo;
import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.retry.Retry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class ComputerFileClient {

    // 搜索连接超时，与 file-server 建立 TCP/HTTP 连接的最长等待。
    private static final int SEARCH_CONNECT_TIMEOUT_MS = 5_000;

    // 读超时余量。file-server 在 timeoutMs 到期后会截断并返回；Client 需略长一点，避免服务端还在收尾时 Client 先断。
    private static final int SEARCH_READ_TIMEOUT_BUFFER_MS = 2_000;

    // Client 读超时上限，防止调用方传入过大 timeoutMs 导致 HTTP 长时间挂起。
    private static final int SEARCH_MAX_READ_TIMEOUT_MS = 20_000;

    // fs 浏览/变更（roots/children/mkdir/rename）读超时：防 file-server 挂起占用 HTTP 线程。
    // Windows 盘符探测/网络盘 stat 单次可达秒级（r最坏仍受最慢单盘拖累）。
    private static final int FS_BROWSE_READ_TIMEOUT_MS = 60_000;

    // 空闲连接淘汰：默认连接池永不淘汰空闲连接，而下游（pingora 入口→沙箱 file-server）连接会随沙箱回收/
    // 中间层被重置，复用死连接表现为 "recvAddress failed: 连接被对方重设"。60s 淘汰 + 后台定期清理规避。
    private static final Duration POOL_MAX_IDLE_TIME = Duration.ofSeconds(60);
    private static final Duration POOL_MAX_LIFE_TIME = Duration.ofMinutes(5);
    private static final Duration POOL_EVICTION_INTERVAL = Duration.ofSeconds(30);
    // 响应读空闲超时（相邻两次网络读之间的最大间隔，非响应总时长）：仅防挂起，持续有数据的流式传输不触发。
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);

    // 文件元数据批量查询单次上限：由本端统一定义并下发给 file-server 执行（file-server 侧另有 1000 硬顶兜底）
    private static final int FILE_META_MAX_BATCH = 100;

    @Resource
    private SandboxServerConfigService sandboxServerConfigService;

    private final WebClient webClient = createWebClient();

    /** 显式配置连接池空闲淘汰与读空闲超时，避免复用已被对端重置的连接 */
    private static WebClient createWebClient() {
        ConnectionProvider provider = ConnectionProvider.builder("computer-file")
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

    private RestTemplate createRestTemplate(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    /** 读超时 = min(业务 timeoutMs + 余量, 上限)；timeoutMs 须由调用方传入正整数。 */
    private int resolveSearchReadTimeoutMs(int timeoutMs) {
        return Math.min(timeoutMs + SEARCH_READ_TIMEOUT_BUFFER_MS, SEARCH_MAX_READ_TIMEOUT_MS);
    }

    private SandboxServerConfig.SandboxServer requireServer(Long cId) {
        SandboxServerConfig.SandboxServer sandboxServer;
        try {
            sandboxServer = sandboxServerConfigService.selectServer(cId);
        } catch (BizException e) {
            // 语义化业务错误（沙箱不在线/被禁用/容量耗尽等）原样上抛，保留真实 code/message，
            // 不压扁成 9198 依赖服务错误
            throw e;
        } catch (Exception e) {
            log.warn("[computer-client] selectServer failed cId={}", cId, e);
            throw BizException.of(BizExceptionCodeEnum.agentDependencyServiceError);
        }
        if (sandboxServer == null || sandboxServer.getServerFileUrl() == null) {
            throw BizException.of(BizExceptionCodeEnum.agentSandboxNotFound);
        }
        return sandboxServer;
    }

    private String fileUrl(SandboxServerConfig.SandboxServer sandboxServer, String path) {
        return sandboxServer.getServerFileUrl() + "/api" + path;
    }

    /** URL 编码一次生成 URI（防 RestTemplate/WebClient 对 String 二次编码），见 {@link UriEncodingSupport} */
    private static URI buildEncodedUri(UriComponentsBuilder builder) {
        return UriEncodingSupport.buildEncodedUri(builder);
    }

    /**
     * 默认超时：无参版兜底，避免 file-server 挂起长期占用 HTTP 线程。
     * 读超时较长（20 分钟）——上传 zip 导入等大包操作在服务端解压期间无数据下发；
     * SO_TIMEOUT 为单次阻塞读上限而非总时长，流式响应只要持续有数据不会触发。
     */
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;

    private static final int DEFAULT_READ_TIMEOUT_MS = 20 * 60_000;

    private RestTemplate createRestTemplate() {
        return createRestTemplate(DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    /**
     * 目录浏览：根列表（Windows 盘符 / POSIX 根）+ 用户 home（目录选择弹窗用，按沙箱定位，无会话场景）
     *
     * @param config 调用方权限校验时已加载的沙箱配置（复用，免二次查询）
     */
    public Map<String, Object> listFsRoots(SandboxConfigRpcDto config) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServerBySandboxId(config);
        URI uri = URI.create(fileUrl(sandboxServer, "/computer/fs/roots"));
        log.info("[computer-client] sandboxId={} 调用文件系统浏览根接口, url={}", config.getId(), uri);
        HttpHeaders headers = new HttpHeaders();
        WorkspaceServiceSupport.applySandboxAuthHeader(headers, sandboxServer.getServerApiKey());
        return executeFsBrowse(uri, HttpMethod.GET, new HttpEntity<>(headers), String.valueOf(config.getId()));
    }

    /**
     * 目录浏览：列出目录下一层子项（目录+文件，目录在前；文件由前端按 isDir 置灰）
     */
    public Map<String, Object> listFsChildren(SandboxConfigRpcDto config, String path) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServerBySandboxId(config);
        URI uri = buildEncodedUri(UriComponentsBuilder.fromHttpUrl(fileUrl(sandboxServer, "/computer/fs/children"))
                .queryParam("path", path));
        log.info("[computer-client] sandboxId={} 调用文件系统浏览子项接口, url={}", config.getId(), uri);
        HttpHeaders headers = new HttpHeaders();
        WorkspaceServiceSupport.applySandboxAuthHeader(headers, sandboxServer.getServerApiKey());
        return executeFsBrowse(uri, HttpMethod.GET, new HttpEntity<>(headers), String.valueOf(config.getId()));
    }

    /**
     * 目录浏览：在指定目录下新建一层目录（目录选择弹窗；参数走 JSON body，重名/父不存在由 file-server 以 400 透出）
     */
    public Map<String, Object> createFsDirectory(SandboxConfigRpcDto config, String parentPath, String dirName) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServerBySandboxId(config);
        URI uri = URI.create(fileUrl(sandboxServer, "/computer/fs/mkdir"));
        Map<String, Object> body = new HashMap<>();
        body.put("parentPath", parentPath);
        body.put("dirName", dirName);
        log.info("[computer-client] sandboxId={} 调用文件系统新建目录接口, url={}, parentPath={}, dirName={}",
                config.getId(), uri, parentPath, dirName);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        WorkspaceServiceSupport.applySandboxAuthHeader(headers, sandboxServer.getServerApiKey());
        return executeFsBrowse(uri, HttpMethod.POST, new HttpEntity<>(body, headers), String.valueOf(config.getId()));
    }

    /**
     * 目录浏览：同目录重命名（newName 仅名字，不支持跨目录移动）
     */
    public Map<String, Object> renameFsDirectory(SandboxConfigRpcDto config, String path, String newName) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServerBySandboxId(config);
        URI uri = URI.create(fileUrl(sandboxServer, "/computer/fs/rename"));
        Map<String, Object> body = new HashMap<>();
        body.put("path", path);
        body.put("newName", newName);
        log.info("[computer-client] sandboxId={} 调用文件系统重命名接口, url={}, path={}, newName={}",
                config.getId(), uri, path, newName);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        WorkspaceServiceSupport.applySandboxAuthHeader(headers, sandboxServer.getServerApiKey());
        return executeFsBrowse(uri, HttpMethod.POST, new HttpEntity<>(body, headers), String.valueOf(config.getId()));
    }

    private SandboxServerConfig.SandboxServer requireServerBySandboxId(SandboxConfigRpcDto config) {
        if (config == null || config.getId() == null || config.getId() <= 0) {
            throw BizException.of(BizExceptionCodeEnum.agentSandboxNotFound);
        }
        // 复用调用方已加载的沙箱配置解析路由，避免与权限校验重复 queryById
        SandboxServerConfig.SandboxServer sandboxServer = sandboxServerConfigService.selectServerBySandboxId(config);
        if (sandboxServer == null || sandboxServer.getServerFileUrl() == null) {
            throw BizException.of(BizExceptionCodeEnum.agentSandboxNotFound);
        }
        return sandboxServer;
    }

    /**
     * fs 浏览/变更公共执行：URI 直传（防二次编码）；4xx 解析下游错误信封透出 message，
     * 5xx/连接异常统一转 transport 错误（消息可展示，不落通用兜底）。
     * 有界超时：目录浏览/新建/重命名均为轻量操作，不套用 20 分钟的大包读超时。
     */
    private Map<String, Object> executeFsBrowse(URI uri, HttpMethod method, HttpEntity<?> requestEntity, String logId) {
        RestTemplate restTemplate = createRestTemplate(SEARCH_CONNECT_TIMEOUT_MS, FS_BROWSE_READ_TIMEOUT_MS);
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(uri, method, requestEntity,
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            return entity.getBody();
        } catch (HttpClientErrorException e) {
            log.warn("[computer-client] {} 调用文件系统浏览接口失败, status={}, responseBody={}",
                    logId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(logId, e);
        } catch (RestClientException e) {
            log.warn("[computer-client] {} 调用文件系统浏览接口异常, error={}", logId, e.getMessage());
            return buildTransportError("file-server unreachable: " + e.getMessage());
        }
    }

    /**
     * 获取文件列表（recursive 默认 true=全量扁平原逻辑；false=单层）
     *
     * @param customTargetDir 目标目录，非空时直接作为查询目录，为空则由服务端按默认规则处理
     * @param relativePath    相对工作区根的目录路径；recursive=false 时用于单层浏览
     * @param recursive       是否递归扁平列出；null 时按 true 下传
     * @param type            返回条目类型 file/dir
     * @param limit           最多返回条目数（非负），file-server 扫描达限提前终止；null 不限
     */
    public Map<String, Object> getFileList(Long conversationUserId, Long cId, String proxyPath, String customTargetDir, String relativePath, Boolean recursive, Integer depth, String type, Integer limit) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        boolean effectiveRecursive = recursive == null || Boolean.TRUE.equals(recursive);
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(fileUrl(sandboxServer, "/computer/get-file-list"))
                .queryParam("userId", conversationUserId)
                .queryParam("cId", cId)
                .queryParam("proxyPath", proxyPath)
                .queryParam("recursive", effectiveRecursive);
        WorkspaceServiceSupport.applyServiceParams(builder, serviceContext);
        if (customTargetDir != null && !customTargetDir.isBlank()) {
            builder.queryParam("customTargetDir", customTargetDir);
        }
        if (relativePath != null && !relativePath.isBlank()) {
            builder.queryParam("relativePath", relativePath);
        }
        if (depth != null) {
            builder.queryParam("depth", depth);
        }
        if (type != null && !type.isBlank()) {
            builder.queryParam("type", type);
        }
        if (limit != null) {
            builder.queryParam("limit", limit);
        }
        URI uri = buildEncodedUri(builder);
        log.info("[computer-client] userId={} cId={} workspaceType={} appId={} 调用获取文件列表接口, url={}, recursive={}, type={}, limit={}",
                conversationUserId, cId, serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), uri, effectiveRecursive, type, limit);

        HttpHeaders headers = new HttpHeaders();
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
        RestTemplate restTemplate = createRestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(uri, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[computer-client] userId={} cId={} 调用获取文件列表接口, 已响应", conversationUserId, cId);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[computer-client] userId={} cId={} 调用获取文件列表接口失败, status={}, responseBody={}", conversationUserId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(conversationUserId + "_" + cId, e);
        }
    }

    /**
     * 批量查询文件元数据（大小/mtime/MIME/扩展名/软链目标/目录子项数），与文件列表解耦按需查询。
     * 批量上限由本端常量 FILE_META_MAX_BATCH 定义并下发 file-server 执行（100 条，file-server 硬顶 1000）；
     * 单条失败（不存在/非法路径）仅该条带 error，不影响整批
     */
    public Map<String, Object> getFileMeta(Long conversationUserId, Long cId, List<String> filePaths, String customTargetDir) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        String url = fileUrl(sandboxServer, "/computer/get-file-meta");
        log.info("[computer-client] userId={} cId={} workspaceType={} appId={} 查询文件元数据, url={}, count={}, maxBatch={}",
                conversationUserId, cId, serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), url, filePaths != null ? filePaths.size() : 0, FILE_META_MAX_BATCH);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("userId", conversationUserId);
        requestBody.put("cId", cId);
        requestBody.put("filePaths", filePaths);
        requestBody.put("fileMetaMaxBatch", FILE_META_MAX_BATCH);
        WorkspaceServiceSupport.applyServiceParams(requestBody, serviceContext);
        if (customTargetDir != null && !customTargetDir.isBlank()) {
            requestBody.put("customTargetDir", customTargetDir);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = createRestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[computer-client] userId={} cId={} 查询文件元数据, 已响应", conversationUserId, cId);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[computer-client] userId={} cId={} 调用文件元数据接口失败, status={}, responseBody={}", conversationUserId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(conversationUserId + "_" + cId, e);
        }
    }

    /**
     * 解析工作区内文件是否存在
     */
    public Map<String, Object> resolveExistingFile(Long conversationUserId, Long cId, String proxyPath, String filePath, String customTargetDir) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(fileUrl(sandboxServer, "/computer/resolve-file"))
                .queryParam("userId", conversationUserId)
                .queryParam("cId", cId)
                .queryParam("filePath", filePath);
        WorkspaceServiceSupport.applyServiceParams(builder, serviceContext);
        if (proxyPath != null && !proxyPath.isBlank()) {
            builder.queryParam("proxyPath", proxyPath);
        }
        if (customTargetDir != null && !customTargetDir.isBlank()) {
            builder.queryParam("customTargetDir", customTargetDir);
        }
        URI uri = buildEncodedUri(builder);
        log.info("[computer-client] userId={} cId={} workspaceType={} appId={} 调用 resolve-file, url={}",
                conversationUserId, cId, serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), uri);

        HttpHeaders headers = new HttpHeaders();
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
        RestTemplate restTemplate = createRestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(uri, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            return entity.getBody();
        } catch (HttpClientErrorException e) {
            log.warn("[computer-client] userId={} cId={} resolve-file 失败, status={}, responseBody={}", conversationUserId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(conversationUserId + "_" + cId, e);
        }
    }

    /**
     * 有界文件搜索；type 为命中类型过滤（file/dir，空=全部），仅过滤输出不影响遍历下钻
     */
    public Map<String, Object> searchFiles(Long conversationUserId, Long cId, String proxyPath, String kw, String customTargetDir, String relativePath, String type, Integer limit, Integer maxVisit, Integer timeoutMs) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(fileUrl(sandboxServer, "/computer/search-files"))
                .queryParam("userId", conversationUserId)
                .queryParam("cId", cId)
                .queryParam("kw", kw);
        WorkspaceServiceSupport.applyServiceParams(builder, serviceContext);
        if (proxyPath != null && !proxyPath.isBlank()) {
            builder.queryParam("proxyPath", proxyPath);
        }
        if (customTargetDir != null && !customTargetDir.isBlank()) {
            builder.queryParam("customTargetDir", customTargetDir);
        }
        if (relativePath != null && !relativePath.isBlank()) {
            builder.queryParam("relativePath", relativePath);
        }
        if (type != null && !type.isBlank()) {
            builder.queryParam("type", type);
        }
        if (limit == null || limit <= 0 || maxVisit == null || maxVisit <= 0 || timeoutMs == null || timeoutMs <= 0) {
            return buildTransportError("limit/maxVisit/timeoutMs are required positive integers");
        }
        builder.queryParam("limit", limit);
        builder.queryParam("maxVisit", maxVisit);
        builder.queryParam("timeoutMs", timeoutMs);
        URI uri = buildEncodedUri(builder);
        log.info("[computer-client] userId={} cId={} workspaceType={} appId={} 调用 search-files, url={}, limit={}, maxVisit={}, timeoutMs={}",
                conversationUserId, cId, serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), uri, limit, maxVisit, timeoutMs);

        HttpHeaders headers = new HttpHeaders();
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
        RestTemplate restTemplate = createRestTemplate(SEARCH_CONNECT_TIMEOUT_MS, resolveSearchReadTimeoutMs(timeoutMs));
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(uri, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            return entity.getBody();
        } catch (HttpClientErrorException.NotFound e) {
            // 404 = 该 file-server 没有 search-files 路由（版本 < 1.3.6）；裸透 "Path not found" 用户无从定位，换成明确的版本提示
            log.warn("[computer-client] userId={} cId={} search-files 404（file-server 版本 < 1.3.6，路由不存在）, responseBody={}",
                    conversationUserId, cId, e.getResponseBodyAsString());
            return buildTransportError("当前沙箱文件服务版本过低，不支持文件搜索（需 file-server ≥ 1.3.6），请升级沙箱文件服务后重试");
        } catch (HttpClientErrorException e) {
            log.warn("[computer-client] userId={} cId={} search-files 失败, status={}, responseBody={}", conversationUserId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(conversationUserId + "_" + cId, e);
        } catch (RestClientException e) {
            log.warn("[computer-client] userId={} cId={} search-files 调用异常, error={}", conversationUserId, cId, e.getMessage());
            return buildTransportError("search-files failed: " + e.getMessage());
        }
    }

    /**
     * 构建静态文件 URL
     * 始终用 targetPrefix + relativePath 拼接路径，customTargetDir 非空时作为 query param 传给 file-server
     */
    private URI buildStaticFileUrl(Long cId, String targetPrefix, String relativePath, String customTargetDir,
                                   SandboxServerConfig.SandboxServer sandboxServer, WorkspaceServiceContext serviceContext) {
        String base = fileUrl(sandboxServer, "");
        String[] prefixSegments = Arrays.stream(targetPrefix.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
        String[] relativeSegments = Arrays.stream(relativePath.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(base)
                .pathSegment(prefixSegments)
                .pathSegment(relativeSegments);
        WorkspaceServiceSupport.applyServiceParams(builder, serviceContext);
        if (customTargetDir != null && !customTargetDir.isBlank()) {
            builder.queryParam("customTargetDir", customTargetDir);
        }
        return buildEncodedUri(builder);
    }

    /**
     * 获取静态文件（流式返回）
     *
     * @param customTargetDir 目标目录，非空时直接使用，为空则用 targetPrefix + relativePath
     */
    public Flux<DataBuffer> getStaticFile(Long cId, String targetPrefix, String relativePath, String logId, String customTargetDir) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        URI uri = buildStaticFileUrl(cId, targetPrefix, relativePath, customTargetDir, sandboxServer, serviceContext);
        log.info("[computer-client] logId={} workspaceType={} appId={} 调用获取静态文件接口, url={}, targetPrefix={}, relativePath={}, customTargetDir={}",
                logId, serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), uri, targetPrefix, relativePath, customTargetDir);

        // 已发出数据则不重试，避免重放整个响应体造成内容重复拼接
        AtomicBoolean emitted = new AtomicBoolean(false);
        return webClient.get()
                .uri(uri)
                .accept(MediaType.ALL) // 接受所有媒体类型，因为静态文件可能是图片、文本等
                .headers(headers -> WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer))
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .doOnNext(dataBuffer -> emitted.set(true))
                .doOnError(WebClientResponseException.class, e -> {
                    log.warn("[computer-client] logId={} 调用获取静态文件接口失败, status={}, responseBody={}", logId, e.getStatusCode(), e.getResponseBodyAsString());
                }).doOnError(Throwable.class, e -> {
                    log.error("[computer-client] logId={} 调用获取静态文件接口异常", logId, e);
                }).doOnDiscard(DataBuffer.class, DataBufferUtils::release).doOnComplete(() -> {
                    log.info("[computer-client] logId={} 调用获取静态文件接口, 流式传输完成", logId);
                })
                // 复用到被对端重置的连接（沙箱回收/长空闲后被中间层断开）时换新连接重试一次
                .retryWhen(Retry.max(1).filter(e -> !emitted.get() && isConnectionError(e)));
    }

    /**
     * 获取静态文件（流式返回，保留状态码与响应头，支持断点续传）
     *
     * @param customTargetDir 目标目录，非空时直接使用，为空则用 targetPrefix + relativePath
     */
    public ResponseEntity<Flux<DataBuffer>> getStaticFile(Long cId, String targetPrefix, String relativePath, String logId, String rangeHeader, String customTargetDir) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        URI uri = buildStaticFileUrl(cId, targetPrefix, relativePath, customTargetDir, sandboxServer, serviceContext);
        log.info("[computer-client] logId={} workspaceType={} appId={} 调用获取静态文件接口(含响应头), url={}, targetPrefix={}, relativePath={}, range={}, customTargetDir={}",
                logId, serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), uri, targetPrefix, relativePath, rangeHeader, customTargetDir);

        try {
            ResponseEntity<Flux<DataBuffer>> response = webClient.get()
                    .uri(uri)
                    .accept(MediaType.ALL)
                    .headers(headers -> {
                        if (rangeHeader != null && !rangeHeader.isBlank()) {
                            headers.set(HttpHeaders.RANGE, rangeHeader);
                        }
                        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);
                    })
                    .retrieve()
                    .toEntityFlux(DataBuffer.class)
                    // 响应头到达前连接被重置（复用死连接）时换新连接重试一次；此处仅重试响应头阶段，body 流不受影响
                    .retryWhen(Retry.max(1).filter(ComputerFileClient::isConnectionError))
                    .block();
            if (response == null || response.getBody() == null) {
                throw new IllegalStateException("Empty downstream response");
            }
            return ResponseEntity.status(response.getStatusCode())
                    .headers(response.getHeaders())
                    .body(response.getBody().doOnDiscard(DataBuffer.class, DataBufferUtils::release));
        } catch (WebClientResponseException e) {
            log.warn("[computer-client] logId={} 调用获取静态文件接口(含响应头)失败, status={}, responseBody={}", logId, e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        } catch (Exception e) {
            log.error("[computer-client] logId={} 调用获取静态文件接口(含响应头)异常", logId, e);
            throw e;
        }
    }

    /**
     * 获取静态文件（阻塞式，返回完整byte[]）
     */
    public byte[] getStaticFileAsBytes(Long cId, String staticPrefix, String relativePath, String logId) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            getStaticFile(cId, staticPrefix, relativePath, logId, null).doOnNext(dataBuffer -> {
                try {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    outputStream.write(bytes);
                } catch (IOException e) {
                    log.error("[computer-client] logId={} 读取DataBuffer失败", logId, e);
                    throw new RuntimeException("读取DataBuffer失败", e);
                } finally {
                    DataBufferUtils.release(dataBuffer);
                }
            }).blockLast();
            byte[] body = outputStream.toByteArray();
            log.info("[computer-client] logId={} 调用获取静态文件接口, 响应大小={} bytes", logId, body != null ? body.length : 0);
            return body;
        } catch (Exception e) {
            log.error("[computer-client] logId={} 获取静态文件失败", logId, e);
            throw new RuntimeException("获取静态文件失败", e);
        }
    }

    /**
     * 更新文件列表
     */
    public Map<String, Object> filesUpdate(Long conversationUserId, Long cId, List<ComputerFileInfo> files, String customTargetDir) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        String url = fileUrl(sandboxServer, "/computer/files-update");
        log.info("[computer-client] userId={} cId={} workspaceType={} appId={} 更新文件列表, url={}, filesCount={}",
                conversationUserId, cId, serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), url, files != null ? files.size() : 0);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("userId", conversationUserId);
        requestBody.put("cId", cId);
        requestBody.put("files", files);
        WorkspaceServiceSupport.applyServiceParams(requestBody, serviceContext);
        if (customTargetDir != null && !customTargetDir.isBlank()) {
            requestBody.put("customTargetDir", customTargetDir);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = createRestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[computer-client] userId={} cId={} 更新文件列表, 响应结果={}", conversationUserId, cId, body);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[computer-client] userId={} cId={} 调用更新文件列表接口失败, status={}, responseBody={}", conversationUserId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(conversationUserId + "_" + cId, e);
        }
    }

    /**
     * 上传文件
     */
    public Map<String, Object> uploadFile(Long conversationUserId, Long cId, String filePath, MultipartFile file, String customTargetDir) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        String url = fileUrl(sandboxServer, "/computer/upload-file");
        log.info("[computer-client] userId={} cId={} workspaceType={} appId={} 上传用户文件, url={}, filePath={}",
                conversationUserId, cId, serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), url, filePath);

        // 创建请求体，包含文件、userId、cId和filePath
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);

        // 创建MultiValueMap来存储文件和其他参数
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file.getResource());
        body.add("userId", String.valueOf(conversationUserId));
        body.add("cId", String.valueOf(cId));
        WorkspaceServiceSupport.applyServiceParams(body, serviceContext);
        body.add("filePath", filePath);
        if (customTargetDir != null && !customTargetDir.isBlank()) {
            body.add("customTargetDir", customTargetDir);
        }

        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = createRestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[computer-client] userId={} cId={} 上传文件, 响应结果={}", conversationUserId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[computer-client] userId={} cId={} 调用上传文件接口失败, status={}, responseBody={}", conversationUserId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(conversationUserId + "_" + cId, e);
        }
    }

    /**
     * 根据文本内容生成文件
     */
    public Map<String, Object> generateFile(Long conversationUserId, Long cId, String fileName, String content, String customTargetDir) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        String url = fileUrl(sandboxServer, "/computer/generate-file");
        log.info("[computer-client] userId={} cId={} workspaceType={} appId={} 生成用户文件, url={}, fileName={}",
                conversationUserId, cId, serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), url, fileName);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("userId", conversationUserId);
        requestBody.put("cId", cId);
        requestBody.put("fileName", fileName);
        requestBody.put("content", content == null ? "" : content);
        WorkspaceServiceSupport.applyServiceParams(requestBody, serviceContext);
        if (customTargetDir != null && !customTargetDir.isBlank()) {
            requestBody.put("customTargetDir", customTargetDir);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = createRestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity,
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[computer-client] userId={} cId={} 生成文件, 响应结果={}", conversationUserId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[computer-client] userId={} cId={} 调用生成文件接口失败, status={}, responseBody={}", conversationUserId, cId,
                    e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(conversationUserId + "_" + cId, e);
        }
    }

    /**
     * 批量上传文件
     */
    public Map<String, Object> uploadFiles(Long conversationUserId, Long cId, List<String> filePaths, List<MultipartFile> files, String customTargetDir) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        String url = fileUrl(sandboxServer, "/computer/upload-files");
        log.info("[computer-client] userId={} cId={} workspaceType={} appId={} 批量上传用户文件, url={}, fileCount={}, filePathsCount={}",
                conversationUserId, cId, serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), url,
                files != null ? files.size() : 0, filePaths != null ? filePaths.size() : 0);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);

        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("userId", String.valueOf(conversationUserId));
        body.add("cId", String.valueOf(cId));
        WorkspaceServiceSupport.applyServiceParams(body, serviceContext);
        if (customTargetDir != null && !customTargetDir.isBlank()) {
            body.add("customTargetDir", customTargetDir);
        }

        // 确保 filePaths 始终作为数组传递，即使只有一个值
        // 使用循环 add 方法确保每个值都作为数组元素传递
        // 这样 RestTemplate 在发送 multipart/form-data 时，会将同一个 key 的多个值作为数组发送
        for (String filePath : filePaths) {
            body.add("filePaths", filePath);
        }

        for (MultipartFile file : files) {
            body.add("files", file.getResource());
        }

        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = createRestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity,
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[computer-client] userId={} cId={} 批量上传文件, 响应结果={}", conversationUserId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[computer-client] userId={} cId={} 调用批量上传文件接口失败, status={}, responseBody={}", conversationUserId, cId,
                    e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(conversationUserId + "_" + cId, e);
        }
    }

    /**
     * 导入项目（zip 替换工作空间）
     */
    public Map<String, Object> importProject(Long conversationUserId, Long cId, MultipartFile file, String customTargetDir) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        String url = fileUrl(sandboxServer, "/computer/import-project");
        log.info("[computer-client] userId={} cId={} workspaceType={} appId={} 导入项目, url={}, fileName={}",
                conversationUserId, cId, serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), url, file.getOriginalFilename());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);

        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file.getResource());
        body.add("userId", String.valueOf(conversationUserId));
        body.add("cId", String.valueOf(cId));
        WorkspaceServiceSupport.applyServiceParams(body, serviceContext);
        if (customTargetDir != null && !customTargetDir.isBlank()) {
            body.add("customTargetDir", customTargetDir);
        }

        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = createRestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity,
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[computer-client] userId={} cId={} 导入项目, 响应结果={}", conversationUserId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[computer-client] userId={} cId={} 调用导入项目接口失败, status={}, responseBody={}", conversationUserId, cId,
                    e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(conversationUserId + "_" + cId, e);
        }
    }

    /**
     * 下载全部文件（zip 流式返回）
     */
    public Flux<DataBuffer> downloadAllFiles(Long conversationUserId, Long cId, String logId, String customTargetDir) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(fileUrl(sandboxServer, "/computer/download-all-files"))
                .queryParam("userId", conversationUserId)
                .queryParam("cId", cId);
        WorkspaceServiceSupport.applyServiceParams(builder, serviceContext);
        if (customTargetDir != null && !customTargetDir.isBlank()) {
            builder.queryParam("customTargetDir", customTargetDir);
        }
        URI uri = buildEncodedUri(builder);
        log.info("[computer-client] logId={} workspaceType={} appId={} 调用下载全部文件接口, url={}",
                logId, serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), uri);

        return webClient.get()
                .uri(uri)
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .headers(headers -> WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer))
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .doOnError(WebClientResponseException.class, e -> {
                    log.warn("[computer-client] logId={} 调用下载全部文件接口失败, status={}, responseBody={}", logId,
                            e.getStatusCode(), e.getResponseBodyAsString());
                })
                .doOnError(Throwable.class, e -> {
                    log.error("[computer-client] logId={} 调用下载全部文件接口异常", logId, e);
                })
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                .doOnComplete(() -> {
                    log.info("[computer-client] logId={} 调用下载全部文件接口, 流式传输完成", logId);
                });
    }

    /**
     * 获取沙盒日志
     */
    public Map<String, Object> getLogs(Long conversationUserId, Long cId, int tailLines) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(fileUrl(sandboxServer, "/computer/get-logs"))
                .queryParam("userId", conversationUserId)
                .queryParam("cId", cId)
                .queryParam("tailLines", tailLines);
        WorkspaceServiceSupport.applyServiceParams(builder, serviceContext);
        URI uri = buildEncodedUri(builder);
        log.info("[computer-client] userId={} cId={} workspaceType={} appId={} 获取沙盒日志, url={}",
                conversationUserId, cId, serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), uri);

        HttpHeaders headers = new HttpHeaders();
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
        RestTemplate restTemplate = createRestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(uri, HttpMethod.GET, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[computer-client] userId={} cId={} 获取沙盒日志, 已响应", conversationUserId, cId);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[computer-client] userId={} cId={} 获取沙盒日志失败, status={}, responseBody={}", conversationUserId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(conversationUserId + "_" + cId, e);
        }
    }

    // 捕获4xx错误，尝试解析响应体
    private Map<String, Object> parseClientErr(String logId, HttpClientErrorException e) {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", false);
        resultMap.put("message", e.getMessage());
        try {
            String responseBody = e.getResponseBodyAsString();
            if (responseBody != null && !responseBody.isEmpty()) {
                ObjectMapper objectMapper = new ObjectMapper();
                @SuppressWarnings("unchecked") Map<String, Object> errorResponse = objectMapper.readValue(responseBody, Map.class);
                if (errorResponse.containsKey("code")) {
                    // 前端会把 4010 当登录态失效跳转登录页；平台的 4010 只能由平台鉴权层产生，
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
            log.error("[computer-client] logId={} 解析错误响应体失败", logId, parseException);
        }
        return resultMap;
    }

    private Map<String, Object> buildTransportError(String message) {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", false);
        resultMap.put("code", "9999");
        resultMap.put("message", message);
        return resultMap;
    }

}
