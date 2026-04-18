package com.xspaceagi.filter;

import com.xspaceagi.sandbox.SandboxApiRewriteProperties;
import com.xspaceagi.sandbox.SandboxRequestAttributes;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.util.List;

/**
 * Sandbox 兼容 API URL 重写过滤器（全局生效）
 */
@Component
@Order(1)
@Slf4j
public class SandboxApiRewriteFilter implements Filter {

    private static final String SANDBOX_PREFIX = "/api/v1/4sandbox";
    /**
     * 允许重写的路径模式（相对于 /api/v1/4sandbox 之后的部分匹配）
     * 支持通配符：*、**（AntPathMatcher）
     */
    private final SandboxApiRewriteProperties rewriteProperties;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public SandboxApiRewriteFilter(SandboxApiRewriteProperties rewriteProperties) {
        this.rewriteProperties = rewriteProperties;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String requestURI = httpRequest.getRequestURI();
        if (requestURI == null || !requestURI.startsWith(SANDBOX_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        // 标记请求来源，供 controller 做沙箱/前端差异处理
        httpRequest.setAttribute(SandboxRequestAttributes.REQUEST_SOURCE, SandboxRequestAttributes.SOURCE_SANDBOX);

        String suffix = requestURI.substring(SANDBOX_PREFIX.length());
        String pathAfterPrefix = suffix.startsWith("/") ? suffix.substring(1) : suffix;

        if (!isAllowRewrite(pathAfterPrefix)) {
            chain.doFilter(request, response);
            return;
        }

        // 保留原始 URI（/api/v1/4sandbox/...），给鉴权拦截器做白名单/AK 判断
        httpRequest.setAttribute(SandboxRequestAttributes.ORIGINAL_REQUEST_URI, requestURI);

        String rewrittenURI = "/api" + suffix; // suffix 以 '/' 开头更安全
        log.debug("Rewrite 4Sandbox API URL: {} -> {}", requestURI, rewrittenURI);

        HttpServletRequest wrappedRequest = new RewriteHttpServletRequestWrapper(httpRequest, rewrittenURI);
        chain.doFilter(wrappedRequest, response);
    }

    private boolean isAllowRewrite(String pathAfterPrefix) {
        List<String> patterns = rewriteProperties.getAllowPath();
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String rawPattern : patterns) {
            String pattern = rawPattern == null ? "" : rawPattern.trim();
            if (pattern.startsWith("/")) {
                pattern = pattern.substring(1);
            }
            if (pattern.isEmpty()) {
                continue;
            }
            if (pathMatcher.match(pattern, pathAfterPrefix)) {
                return true;
            }
        }
        return false;
    }

    private static class RewriteHttpServletRequestWrapper extends HttpServletRequestWrapper {

        private final String rewrittenURI;

        public RewriteHttpServletRequestWrapper(HttpServletRequest request, String rewrittenURI) {
            super(request);
            this.rewrittenURI = rewrittenURI;
        }

        @Override
        public String getRequestURI() {
            return rewrittenURI;
        }

        @Override
        public String getServletPath() {
            // Spring MVC 路由匹配使用 servletPath/requestURI 等信息；统一返回改写后的路径。
            return rewrittenURI;
        }
    }
}

