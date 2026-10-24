package com.xspaceagi.repo.web.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.repo.web.dto.sidecar.SidecarAiEditRequest;
import com.xspaceagi.repo.web.dto.sidecar.SidecarAiEditResult;
import com.xspaceagi.repo.web.dto.sidecar.SidecarExportRequest;
import com.xspaceagi.repo.web.dto.sidecar.SidecarImportRequest;
import com.xspaceagi.repo.web.dto.sidecar.SidecarImportResult;
import com.xspaceagi.repo.web.dto.sidecar.SidecarTreeChangedResult;
import com.xspaceagi.system.spec.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 文档协作 Sidecar（nuwax-repo-collab :9090）HTTP 客户端。
 * <p>
 * 用于沙箱文档 API（{@code /api/v1/4sandbox/repo}）转发三类内部端点：
 * <ul>
 *   <li>{@code /internal/import} —— 导入（须带 ticket 供 Sidecar 回调平台创建页面/上传原件）；</li>
 *   <li>{@code /internal/ai-edit} —— 读正文 / 定点修改正文；</li>
 *   <li>{@code /internal/export} —— sheet→xlsx、pptx→pptx 二进制导出。</li>
 * </ul>
 * 鉴权统一走 {@code X-Collab-Secret}（与 {@code repo.collab.internal-secret} 一致），非 2xx 或
 * sidecar 返回 {@code ok:false} 时抛 {@link BizException}，由调用方转统一错误响应。
 */
@Slf4j
@Component
public class RepoCollabSidecarClient {

    private static final String SECRET_HEADER = "X-Collab-Secret";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;

    private final String baseUrl;

    private final String internalSecret;

    public RepoCollabSidecarClient(
            @Value("${repo.collab.base-url:http://127.0.0.1:9090}") String baseUrl,
            @Value("${repo.collab.internal-secret:}") String internalSecret) {
        this.baseUrl = (baseUrl == null ? "" : baseUrl).replaceAll("/+$", "");
        this.internalSecret = internalSecret;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30_000);
        factory.setReadTimeout(120_000);
        this.restTemplate = new RestTemplate(factory);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(SECRET_HEADER, internalSecret);
        return headers;
    }

    private <T> T post(String path, Object body, Class<T> respType) {
        try {
            ResponseEntity<T> resp = restTemplate.postForEntity(
                    baseUrl + path, new HttpEntity<>(body, headers()), respType);
            if (resp.getBody() == null) {
                throw new BizException("sidecar empty response");
            }
            return resp.getBody();
        } catch (HttpStatusCodeException e) {
            throw new BizException(readError(e));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("sidecar call failed: path={}, error={}", path, e.getMessage());
            throw new BizException("sidecar unavailable: " + e.getMessage());
        }
    }

    private String readError(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        if (body != null && !body.isBlank()) {
            try {
                JsonNode node = MAPPER.readTree(body);
                if (node.has("error") && !node.get("error").isNull()) {
                    return node.get("error").asText();
                }
            } catch (Exception ignore) {
                // 非 JSON 响应，回落 HTTP 状态
            }
        }
        return "sidecar error: HTTP " + e.getStatusCode().value();
    }

    /** 导入文档（Sidecar 内部创建页面 + 深度解析 + 落 ydoc_state） */
    public SidecarImportResult importDoc(SidecarImportRequest body) {
        SidecarImportResult result = post("/internal/import", body, SidecarImportResult.class);
        if (!result.isOk()) {
            throw new BizException(result.getError() == null ? "import failed" : result.getError());
        }
        return result;
    }

    /** 读正文：kind 显式传入（按页面类型映射），避免全新空表按内容探测误判；返回 doc / slides / snapshot 三选一 */
    public SidecarAiEditResult readContent(String documentName, String kind) {
        SidecarAiEditRequest req = new SidecarAiEditRequest();
        req.setDocumentName(documentName);
        req.setKind(kind);
        req.setOps(List.of(Map.of("op", "read")));
        SidecarAiEditResult result = post("/internal/ai-edit", req, SidecarAiEditResult.class);
        if (!result.isOk()) {
            throw new BizException(result.getError() == null ? "read failed" : result.getError());
        }
        return result;
    }

    /** 定点修改正文（结构化编辑指令，按序执行）。pageId/ticket 供 Sidecar 编辑 doc 后回写快照（docx/pdf/markdown 导出实时） */
    public SidecarAiEditResult editContent(String documentName, String kind, Long editorId,
                                           List<Map<String, Object>> ops, Long pageId, String ticket) {
        SidecarAiEditRequest req = new SidecarAiEditRequest();
        req.setDocumentName(documentName);
        req.setKind(kind);
        req.setEditorId(editorId);
        req.setOps(ops);
        req.setPageId(pageId);
        req.setTicket(ticket);
        SidecarAiEditResult result = post("/internal/ai-edit", req, SidecarAiEditResult.class);
        if (!result.isOk()) {
            throw new BizException(result.getError() == null ? "edit failed" : result.getError());
        }
        return result;
    }

    /**
     * 树结构变更广播：skill API 新建/删除/导入页面后通知 Sidecar 把 {spaceId} 写进全部内存文档的
     * Yjs meta.treeVersion（借 Hocuspocus 广播），驱动开着同空间文档的协同者实时刷新左侧目录树。
     * 失败抛 {@link BizException}，调用方按尽力而为捕获（不阻断建/删/导入主操作）。
     */
    public void treeChanged(Long spaceId) {
        SidecarTreeChangedResult result =
                post("/internal/tree-changed", Map.of("spaceId", spaceId), SidecarTreeChangedResult.class);
        if (!result.isOk()) {
            throw new BizException(result.getError() == null ? "tree-changed failed" : result.getError());
        }
    }

    /** 导出二进制（xlsx/pptx），ticket 供 Sidecar 拉取图片/媒体 */
    public byte[] export(String documentName, String format, String fileName, String ticket) {
        SidecarExportRequest req = new SidecarExportRequest();
        req.setDocumentName(documentName);
        req.setFormat(format);
        req.setFileName(fileName);
        req.setTicket(ticket);
        try {
            ResponseEntity<byte[]> resp = restTemplate.exchange(
                    baseUrl + "/internal/export", HttpMethod.POST, new HttpEntity<>(req, headers()), byte[].class);
            return resp.getBody() == null ? new byte[0] : resp.getBody();
        } catch (HttpStatusCodeException e) {
            throw new BizException(readError(e));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("sidecar export failed: error={}", e.getMessage());
            throw new BizException("sidecar unavailable: " + e.getMessage());
        }
    }
}
