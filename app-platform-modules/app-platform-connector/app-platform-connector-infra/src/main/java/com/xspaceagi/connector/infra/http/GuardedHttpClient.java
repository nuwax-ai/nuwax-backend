package com.xspaceagi.connector.infra.http;

import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.connector.spec.exception.ConnectorException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 出站安全 HTTP 客户端（设计文档 §4.7 GuardedHttpClient，连接器模块内新建）。
 * <ul>
 *   <li>URL 字面量校验 + DNS 预解析地址校验（双保险）</li>
 *   <li>reserved 段永不放行：loopback / link-local / 云元数据（含阿里云 100.100.100.200）/ IPv6 ULA</li>
 *   <li>private 段默认拒绝，全局开关 opt-in（自托管场景）</li>
 *   <li>重定向手工逐跳校验（最多 5 跳）；跨源重定向剥离凭证头</li>
 *   <li>connect/request timeout 真正生效；响应体大小上限</li>
 * </ul>
 */
@Slf4j
@Component
public class GuardedHttpClient {

    private static final int MAX_REDIRECTS = 5;
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");
    /** 凭证类请求头：跨源重定向时必须剥离 */
    private static final Set<String> CREDENTIAL_HEADERS = Set.of(
            "authorization", "cookie", "proxy-authorization", "x-api-key");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** private 网段 opt-in 开关（自托管场景，默认关闭） */
    @Value("${nuwax.connector.guard.allow-private:false}")
    private boolean allowPrivate;

    /** 拦截开关：false=观察模式（只记录不拦截） */
    @Value("${nuwax.connector.guard.enforce:true}")
    private boolean enforce;

    /** 全局超时上限（毫秒） */
    @Value("${nuwax.connector.guard.timeout-ms:60000}")
    private long globalTimeoutMs;

    /** 响应体大小上限（字节） */
    @Value("${nuwax.connector.guard.max-response-bytes:1048576}")
    private long maxResponseBytes;

    /**
     * 执行受保护的 HTTP 请求。
     *
     * @param method    GET/POST/PUT/PATCH/DELETE/HEAD
     * @param url       完整 URL（已拼装 base_url + path + query）
     * @param headers   请求头（含已注入的凭证）
     * @param body      请求体，可空
     * @param timeoutMs 请求超时（与全局上限取小者），&lt;=0 用全局上限
     */
    public GuardedHttpResponse execute(String method, String url, Map<String, String> headers,
                                       byte[] body, long timeoutMs) {
        String normalizedMethod = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(normalizedMethod)) {
            throw new ConnectorException(ConnectorErrorCode.PROXY_METHOD_NOT_ALLOWED,
                    "方法不在白名单: " + method);
        }
        long effectiveTimeout = timeoutMs <= 0 ? globalTimeoutMs : Math.min(timeoutMs, globalTimeoutMs);

        URI uri = URI.create(url);
        guardUri(uri);

        Map<String, String> currentHeaders = new LinkedHashMap<>();
        if (headers != null) {
            currentHeaders.putAll(headers);
        }
        URI currentUri = uri;
        String originHost = uri.getHost();

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(currentUri)
                    .timeout(Duration.ofMillis(effectiveTimeout));
            for (Map.Entry<String, String> entry : currentHeaders.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
            if (body != null && !normalizedMethod.equals("GET") && !normalizedMethod.equals("HEAD")) {
                builder.method(normalizedMethod, HttpRequest.BodyPublishers.ofByteArray(body));
            } else {
                builder.method(normalizedMethod, HttpRequest.BodyPublishers.noBody());
            }

            long start = System.currentTimeMillis();
            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (java.net.http.HttpTimeoutException e) {
                throw new ConnectorException(ConnectorErrorCode.UPSTREAM_TIMEOUT,
                        "上游调用超时(" + effectiveTimeout + "ms): " + safeUrl(currentUri), e);
            } catch (java.io.IOException e) {
                throw new ConnectorException(ConnectorErrorCode.UPSTREAM_ERROR,
                        "上游网络错误: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConnectorException(ConnectorErrorCode.UPSTREAM_ERROR, "上游调用被中断", e);
            }

            int status = response.statusCode();
            if (isRedirect(status)) {
                String location = response.headers().firstValue("Location").orElse(null);
                closeQuietly(response.body());
                if (location == null || hop == MAX_REDIRECTS) {
                    throw new ConnectorException(ConnectorErrorCode.UPSTREAM_ERROR,
                            "重定向无 Location 或超过 " + MAX_REDIRECTS + " 跳", Map.of("httpStatus", status));
                }
                URI next = currentUri.resolve(location);
                guardUri(next);
                // 跨源重定向剥离凭证头
                if (!sameOrigin(originHost, next)) {
                    currentHeaders.keySet().removeIf(name -> CREDENTIAL_HEADERS.contains(name.toLowerCase(Locale.ROOT)));
                }
                currentUri = next;
                continue;
            }

            byte[] responseBody = readCapped(response.body());
            long duration = System.currentTimeMillis() - start;
            Map<String, List<String>> responseHeaders = response.headers().map();
            return new GuardedHttpResponse(status, responseHeaders, responseBody, duration);
        }
        throw new ConnectorException(ConnectorErrorCode.UPSTREAM_ERROR, "重定向跳数超限");
    }

    // ---------- SSRF 防护 ----------

    private void guardUri(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            reject("非法协议: " + scheme);
            return;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            reject("缺少主机名");
            return;
        }
        int port = uri.getPort();
        if (port != -1 && (port <= 0 || port > 65535)) {
            reject("非法端口: " + port);
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new ConnectorException(ConnectorErrorCode.UPSTREAM_ERROR, "DNS 解析失败: " + host, e);
        }
        for (InetAddress address : addresses) {
            checkAddress(address);
        }
    }

    private void checkAddress(InetAddress address) {
        if (address.isLoopbackAddress()) {
            reject("loopback 地址: " + address.getHostAddress());
            return;
        }
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
            reject("保留地址: " + address.getHostAddress());
            return;
        }
        if (address.isLinkLocalAddress()) {
            // 含云元数据 169.254.169.254
            reject("link-local/元数据地址: " + address.getHostAddress());
            return;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            // 阿里云元数据 100.100.100.200
            if (b0 == 100 && b1 == 100 && (bytes[2] & 0xFF) == 100 && (bytes[3] & 0xFF) == 200) {
                reject("云元数据地址: " + address.getHostAddress());
                return;
            }
            if (address.isSiteLocalAddress() && !allowPrivate) {
                // 10/8、172.16/12、192.168/16
                reject("private 网段默认拒绝: " + address.getHostAddress());
                return;
            }
        } else if (address instanceof Inet6Address) {
            // fc00::/7 ULA
            if ((bytes[0] & 0xFE) == 0xFC && !allowPrivate) {
                reject("IPv6 ULA 默认拒绝: " + address.getHostAddress());
            }
        }
    }

    /**
     * 拦截或观察：enforce=true 抛 SSRF_BLOCKED；false 仅记录后放行。
     */
    private void reject(String reason) {
        if (!enforce) {
            log.warn("[connector] guard 观察模式，不拦截: {}", reason);
            return;
        }
        log.warn("[connector] guard 拦截: {}", reason);
        throw new ConnectorException(ConnectorErrorCode.SSRF_BLOCKED, "出站被安全策略拦截: " + reason);
    }

    // ---------- 工具 ----------

    private byte[] readCapped(InputStream stream) {
        try (stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            long total = 0;
            int read;
            while ((read = stream.read(chunk)) != -1) {
                total += read;
                if (total > maxResponseBytes) {
                    throw new ConnectorException(ConnectorErrorCode.UPSTREAM_RESPONSE_TOO_LARGE,
                            "响应体超过上限 " + maxResponseBytes + " 字节");
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException(ConnectorErrorCode.UPSTREAM_ERROR, "读取响应失败: " + e.getMessage(), e);
        }
    }

    private boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER || status == 307 || status == 308;
    }

    private boolean sameOrigin(String originHost, URI next) {
        return originHost != null && next.getHost() != null
                && originHost.equalsIgnoreCase(next.getHost());
    }

    private void closeQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private String safeUrl(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPath() == null ? "" : uri.getPath());
    }

    /**
     * 受保护响应。
     */
    public record GuardedHttpResponse(int status, Map<String, List<String>> headers, byte[] body, long durationMs) {

        public String bodyAsString() {
            return body == null ? "" : new String(body, StandardCharsets.UTF_8);
        }
    }
}
