package com.xspaceagi.repo.application.collab;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 目录树结构变更广播（application 层，SDK 与 HTTP 两条路径共用）。
 * <p>
 * 调用 Sidecar {@code /internal/tree-changed}，把 {@code {spaceId}} 写进全部在线文档的
 * Yjs {@code meta.treeVersion}（借 Hocuspocus 广播），驱动开着同空间文档的协同者实时刷新左侧目录树。
 * <p>
 * 尽力而为：任何失败仅记 warn，不抛异常、不阻断 create/delete/move/copy/rename 主操作
 * （页面真源已落库，信号仅触达刷树）。
 */
@Slf4j
@Component
public class RepoTreeChangeNotifier {

    private static final String SECRET_HEADER = "X-Collab-Secret";

    private final HttpClient httpClient;

    private final String baseUrl;

    private final String internalSecret;

    public RepoTreeChangeNotifier(
            @Value("${repo.collab.base-url:http://127.0.0.1:9090}") String baseUrl,
            @Value("${repo.collab.internal-secret:}") String internalSecret) {
        this.baseUrl = (baseUrl == null ? "" : baseUrl).replaceAll("/+$", "");
        this.internalSecret = internalSecret;
        // 必须显式指定 HTTP/1.1：JDK HttpClient 默认 HTTP/2，对 http:// 明文会先发 `Upgrade: h2c` 协商；
        // Sidecar 是 Node（llhttp）解析器，遇到该请求直接判非法，回 Node 内置的 405 "Invalid HTTP method"
        // ——请求根本进不到 /internal/tree-changed 处理器（secret 校验都不执行，Sidecar 侧无任何日志痕迹）。
        // 2026-09-12 在线上机（JDK17）同构实测：默认 HTTP_2 → 405，HTTP_1_1 → 200 {"ok":true,"broadcast":1}。
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 广播树结构变更（尽力而为）。
     *
     * @param spaceId 发生结构变更的空间ID，null 时直接忽略
     */
    public void notifyTreeChanged(Long spaceId) {
        if (spaceId == null) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/internal/tree-changed"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header(SECRET_HEADER, internalSecret)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"spaceId\":" + spaceId + "}"))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("tree-changed broadcast failed: spaceId={}, http={}", spaceId, resp.statusCode());
            }
        } catch (Exception e) {
            log.warn("tree-changed broadcast failed: spaceId={}, error={}", spaceId, e.getMessage());
        }
    }
}
