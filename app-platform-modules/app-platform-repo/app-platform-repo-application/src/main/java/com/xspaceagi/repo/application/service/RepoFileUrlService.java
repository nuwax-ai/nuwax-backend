package com.xspaceagi.repo.application.service;

import com.xspaceagi.file.sdk.IFileAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档输出文件地址补 ak（需求6）：把内容里的裸 /api/f/{key} 相对地址换成带访问凭证的绝对地址，
 * 未登录端（匿名分享页）无需 cookie 即可直访。
 * <ul>
 *   <li>local 存储 → {@code {base}/api/f/{key}?ak=xxx}（Redis file.ak 24h）；</li>
 *   <li>云存储 → 24h 预签名直链（returnOriginalUrl=true）。</li>
 * </ul>
 * 幂等：已带查询串（含 ?ak=）的地址跳过。仅作用于输出路径（分享视图），
 * 版本预览/快照写回不经过本服务——存储层永远是干净的裸地址。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoFileUrlService {

    /**
     * 匹配 /api/f/{fileKey}：fileKey 多级路径（字母数字 ._-% /），在引号/尖括号/空白/反斜杠处截断
     */
    private static final Pattern FILE_URL_PATTERN = Pattern.compile("/api/f/[A-Za-z0-9._%\\-/]+");

    private final IFileAccessService fileAccessService;

    /**
     * 给 html/JSON 字符串中的文件地址补 ak。null/不含文件地址原样返回；单个地址失败不影响其余。
     *
     * @param content        内容（html 快照或 ProseMirror JSON 字符串）
     * @param requestBaseUrl 请求来源绝对基址（如 http://host:port），拼成绝对 URL 交给文件服务
     * @return 补 ak 后的内容
     */
    public String enrichFileUrlAk(String content, String requestBaseUrl) {
        if (content == null || content.isEmpty() || !content.contains("/api/f/")) {
            return content;
        }
        Matcher matcher = FILE_URL_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder(content.length() + 256);
        int last = 0;
        boolean changed = false;
        while (matcher.find()) {
            int end = matcher.end();
            // 幂等：后面已跟查询串（?ak=… 或其他参数）的跳过
            if (end < content.length() && (content.charAt(end) == '?' || content.charAt(end) == '&')) {
                continue;
            }
            String relative = matcher.group();
            String enriched = enrichOne(relative, requestBaseUrl);
            if (enriched == null) {
                continue;
            }
            sb.append(content, last, matcher.start()).append(enriched);
            last = end;
            changed = true;
        }
        if (!changed) {
            return content;
        }
        sb.append(content, last, content.length());
        return sb.toString();
    }

    /**
     * 单个地址补 ak：失败或无变化返回 null（调用方保留原文）
     */
    private String enrichOne(String relativeUrl, String requestBaseUrl) {
        try {
            String absUrl = joinBase(requestBaseUrl, relativeUrl);
            if (absUrl == null) {
                return null;
            }
            String result = fileAccessService.getFileUrlWithAk(absUrl, true);
            if (result == null || result.equals(absUrl) || result.equals(relativeUrl)) {
                return null;
            }
            return result;
        } catch (Exception e) {
            log.debug("enrich file ak failed, url={}, error={}", relativeUrl, e.getMessage());
            return null;
        }
    }

    private String joinBase(String baseUrl, String relativeUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + relativeUrl;
    }

    /**
     * 单个文件地址换可直访地址（需求6 补充：前端 html 预览用）。
     * html 原样预览渲染在 sandbox（opaque origin）iframe 内，请求不带登录 cookie，
     * 裸 /api/f/{key} 会被拒；前端渲染前经本方法换成带 ak / 预签名的绝对地址。
     * 失败或无变化时原样返回（前端保留原地址）。
     */
    public String enrichSingleUrl(String relativeUrl, String requestBaseUrl) {
        if (relativeUrl == null || relativeUrl.isBlank()) {
            return relativeUrl;
        }
        String enriched = enrichOne(relativeUrl, requestBaseUrl);
        return enriched != null ? enriched : relativeUrl;
    }
}
