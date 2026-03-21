package com.xspaceagi.mcp.infra.rpc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.eco.market.sdk.reponse.ClientSecretResponse;
import com.xspaceagi.eco.market.sdk.request.ClientSecretRequest;
import com.xspaceagi.mcp.infra.client.HttpClientSseClientTransport;
import com.xspaceagi.mcp.infra.client.McpAsyncClient;
import com.xspaceagi.mcp.infra.client.McpAsyncClientWrapper;
import com.xspaceagi.mcp.infra.client.McpClient;
import com.xspaceagi.mcp.infra.rpc.dto.McpDeployStatusResponse;
import com.xspaceagi.mcp.infra.rpc.enums.McpPersistentTypeEnum;
import com.xspaceagi.mcp.spec.utils.UrlExtractUtil;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.HttpClient;
import com.xspaceagi.system.spec.utils.MD5;
import io.modelcontextprotocol.spec.McpError;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.http.HttpRequest;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Service
@Slf4j
public class McpDeployRpcService {

    static {
        System.setProperty("jdk.httpclient.keepalive.timeout", "0");
    }

    private static final String MCP_VERSION_KEY = "mcp-version:";
    @Value("${mcp.proxy-base-url:}")
    private String mcpProxyBaseUrl;

    @Resource
    private HttpClient httpClient;

    @Resource
    private MarketplaceRpcService marketplaceRpcService;

    private java.net.http.HttpClient sseHttpClient = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    public McpDeployStatusResponse deploy(String mcpId, String serverConfig, McpPersistentTypeEnum persistentType) {
        String statusApi = mcpProxyBaseUrl + "/mcp/sse/check_status";
        String mcpKey = getDeployMcpKey(mcpId, serverConfig);
        JSONObject params = new JSONObject();
        params.put("mcpId", mcpKey);
        params.put("mcpType", persistentType.name());
        params.put("mcpJsonConfig", JSON.parseObject(serverConfig).toJSONString());
        String content = httpClient.post(statusApi, params.toJSONString(), new HashMap<>());
        log.debug("Mcp deploy result: {}", content);
        ReqResult result = JSON.parseObject(content, ReqResult.class);
        if (!result.isSuccess()) {
            log.warn("Mcp deploy failed: {}", result.getMessage());
            throw new BizException(result.getMessage());
        }
        McpDeployStatusResponse mcpDeployStatusResponse = ((JSONObject) result.getData()).toJavaObject(McpDeployStatusResponse.class);
        if (mcpDeployStatusResponse == null) {
            throw new BizException("McpDeployStatusResponse is null");
        }
        return mcpDeployStatusResponse;
    }

    public Mono<McpAsyncClientWrapper> getMcpAsyncClient(String mcpId, String conversationId, String serverConfig) {
        Object val = SimpleJvmHashCache.removeHash(getPoolKey(mcpId), conversationId);
        if (val != null) {
            return Mono.just((McpAsyncClientWrapper) val);
        }
        Object mcpKey = getMcpKey(mcpId, conversationId);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .header("content-type", "application/json")
                .header("x-mcp-json", Base64.encodeBase64String(serverConfig.getBytes(Charset.forName("UTF-8"))))//"{\"mcpServers\":{\"@upstash/context7-mcp\":{\"command\":\"npx1\",\"args\":[\"-y\",\"@mcp_hub_org/cli@latest\",\"run\",\"@upstash/context7-mcp\"]}}}")//
                .header("x-mcp-type", "OneShot");
        HttpClientSseClientTransport.Builder httpClientSseClientTransportBuilder = HttpClientSseClientTransport.builder(mcpProxyBaseUrl)
                .sseEndpoint("/mcp/sse/proxy/" + mcpKey + "/sse")
                .httpClient(sseHttpClient)
                .requestBuilder(builder);
        return buildClientWrapper(httpClientSseClientTransportBuilder, mcpId, serverConfig);
    }

    public Mono<McpAsyncClientWrapper> getMcpAsyncClientForSSE(String mcpId, String conversationId, String serverConfig) {
        //从serverConfig中解析出sse地址
        List<String> list = UrlExtractUtil.extractUrls(serverConfig);
        if (list.size() == 0) {
            return Mono.error(new IllegalArgumentException("Invalid serverConfig"));
        }
        Object val = SimpleJvmHashCache.removeHash(getPoolKey(mcpId), conversationId);
        if (val != null) {
            return Mono.just((McpAsyncClientWrapper) val);
        }

        String baseUrl;
        String endpoint;
        try {
            URL urlObj = new URL(list.get(0));
            // 1. 获取根地址（协议 + 域名 + 端口）
            String protocol = urlObj.getProtocol();
            String host = urlObj.getHost();
            int port = urlObj.getPort();
            baseUrl = protocol + "://" + host;
            if (port != -1) {
                baseUrl += ":" + port;
            }
            // 2. 获取带参数的URI路径
            String path = urlObj.getPath();
            String query = urlObj.getQuery();
            String fullUri = path;
            if (query != null) {
                fullUri += "?" + query;
            }

            endpoint = fullUri;
            try {
                ClientSecretResponse clientSecretResponse = marketplaceRpcService.queryClientSecret(new ClientSecretRequest(RequestContext.get().getTenantId()));
                if (clientSecretResponse != null) {
                    endpoint = endpoint.replace("TENANT_SECRET", clientSecretResponse.getClientSecret());
                }
            } catch (Exception e) {
                // ignore
                log.warn("queryClientSecret error", e);
            }
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
        HttpClientSseClientTransport.Builder builder = HttpClientSseClientTransport.builder(baseUrl);
        builder.sseEndpoint(endpoint);
        builder.httpClient(sseHttpClient);
        try {
            Map<String, String> headers = UrlExtractUtil.extractHeaders(serverConfig);
            if (headers != null && headers.size() > 0) {
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
                requestBuilder.header("Content-Type", "application/json");
                headers.forEach((k, v) -> requestBuilder.header(k, v));
                builder.requestBuilder(requestBuilder);
            }
        } catch (Exception e) {
            // ignore
            log.warn("extractHeaders error", e);
        }
        return buildClientWrapper(builder, mcpId, serverConfig);
    }

    private Mono<McpAsyncClientWrapper> buildClientWrapper(HttpClientSseClientTransport.Builder builder, String mcpId, String serverConfig) {
        return Mono.create(emitter -> {
            HttpClientSseClientTransport httpClientSseClientTransport = builder.build();
            McpAsyncClient client = McpClient.async(httpClientSseClientTransport).requestTimeout(Duration.ofMinutes(30)).build();
            client.initialize().timeout(Duration.ofSeconds(20))
                    .onErrorResume(throwable -> {
                        if (throwable instanceof TimeoutException) {
                            log.warn("MCP {} initialize timeout", mcpId);
                            return Mono.error(new McpError("MCP initialize timeout"));
                        }
                        return Mono.error(new McpError(throwable.getMessage()));
                    }).doOnError((throwable) -> {
                        log.warn("MCP {} initialize failed", mcpId);
                        httpClientSseClientTransport.close();
                        client.close();
                        if (throwable instanceof McpError) {
                            emitter.error(throwable);
                            return;
                        }
                        emitter.error(new McpError("MCP initialize failed"));
                    }).doOnSuccess(result -> {
                        log.info("MCP {} initialized: {}", mcpId, serverConfig);
                        McpAsyncClientWrapper clientWrapper = McpAsyncClientWrapper.builder().httpClientSseClientTransport(httpClientSseClientTransport)
                                .client(client).build();
                        emitter.success(clientWrapper);
                    }).subscribe();
        });
    }

    private String getMcpKey(String mcpId, String conversationId) {
        return mcpId + "-" + conversationId;
    }

    public void returnMcpClient(String mcpId, String conversationId, McpAsyncClientWrapper mcpAsyncClientWrapper) {
        log.debug("returnMcpClient: {} {} {}", mcpId, conversationId, mcpAsyncClientWrapper);
        if (conversationId == null) {
            mcpAsyncClientWrapper.close();
            return;
        }
        Object val = SimpleJvmHashCache.getHash(getPoolKey(mcpId), conversationId);
        if (val != mcpAsyncClientWrapper) {
            try {
                ((McpAsyncClient) val).close();
            } catch (Exception e) {
                //  ignore
            }
        }
        //缓存30秒，没有再使用就关闭；
        SimpleJvmHashCache.putHash(getPoolKey(mcpId), conversationId, mcpAsyncClientWrapper, 30, (mcpClient) -> {
            mcpAsyncClientWrapper.close();
        });
    }

    public void closeMcpClient(String mcpId, String conversationId, McpAsyncClientWrapper mcpAsyncClientWrapper) {
        log.debug("closeMcpClient: {} {} {}", mcpId, conversationId, mcpAsyncClientWrapper);
        if (mcpAsyncClientWrapper == null) {
            mcpAsyncClientWrapper = (McpAsyncClientWrapper) SimpleJvmHashCache.getHash(getPoolKey(mcpId), conversationId);
        }
        SimpleJvmHashCache.removeHash(getPoolKey(mcpId), conversationId);
        if (mcpAsyncClientWrapper != null) {
            mcpAsyncClientWrapper.close();
            String deleteApi = mcpProxyBaseUrl + "/mcp/config/delete/{mcp_id}";
            httpClient.delete(deleteApi.replace("{mcp_id}", getMcpKey(mcpId, conversationId)), new HashMap<>());
        }
    }

    private String getDeployMcpKey(String mcpId, String serverConfig) {
        return getMcpKey(mcpId, MD5.MD5Encode(serverConfig));
    }

    private String getPoolKey(String mcpId) {
        return MCP_VERSION_KEY + mcpId;
    }
}
