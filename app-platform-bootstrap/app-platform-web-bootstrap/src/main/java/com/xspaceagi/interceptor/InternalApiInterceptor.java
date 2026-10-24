package com.xspaceagi.interceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 内部服务间 HTTP API 认证拦截器（/api/internal/**，collab 除外）。
 * <p>
 * 供其他项目以 server-to-server 方式调用平台内部查询接口。与 {@link ApiKeyInterceptor}
 * 的 ak-/ck- 用户级 AccessKey 体系不同，这里校验的是<b>配置文件里的静态共享密钥</b>：
 * 请求头 {@code X-Api-Key} 必须与 {@code internal.api.key} 一致。
 * <p>
 * 安全语义（对齐 RepoInternalAuthController 的 X-Collab-Secret 方案）：
 * <ul>
 *   <li>fail-closed：{@code internal.api.key} 未配置（空白）视为内部 API 未启用，一律拒绝；</li>
 *   <li>常量时间比较（{@link MessageDigest#isEqual}），防时序侧信道；</li>
 *   <li>日志不输出密钥本身，只输出「是否已配置」与请求路径；</li>
 *   <li>/api/internal/collab/** 在注册处排除 —— 该命名空间由 Sidecar 共享密钥
 *       X-Collab-Secret 自行认证（见 RepoInternalAuthController），两套机制互不干扰。</li>
 * </ul>
 * 拒绝时抛 {@link BizException}（apiKeyMissing 3170 / apiKeyInvalid 3167），
 * 由 AppExceptionHandler 统一转 JSON 错误响应，与 ApiKeyInterceptor 同款。
 * <p>
 * 租户语义：本拦截器只管认证，不解析租户。这些路径在 auth.excludePath 中豁免登录，
 * AuthInterceptor 仍会先落一个默认租户上下文（tenantId=1），由各接口显式按入参
 * tenantId 覆盖（InternalApiController#applyTenant），保证查询严格限定在调用方声明的租户内。
 */
@Slf4j
@Component
public class InternalApiInterceptor implements HandlerInterceptor {

    public static final String API_KEY_HEADER = "X-Api-Key";

    /** 配置文件静态密钥；空白 = 内部 API 未启用，所有请求一律拒绝（fail-closed） */
    @Value("${internal.api.key:}")
    private String configuredApiKey;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS 预检不携带自定义头，放行（预检不触达业务逻辑，真实请求仍会校验）
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (StringUtils.isBlank(apiKey)) {
            log.warn("Internal api rejected: missing {} header, uri={}", API_KEY_HEADER, request.getRequestURI());
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.apiKeyMissing);
        }
        if (StringUtils.isBlank(configuredApiKey)
                || !MessageDigest.isEqual(configuredApiKey.getBytes(StandardCharsets.UTF_8),
                        apiKey.getBytes(StandardCharsets.UTF_8))) {
            // 未配置与配置不匹配合并为同一拒绝语义，避免向调用方泄露「服务端是否已启用」
            log.warn("Internal api rejected: invalid {} header (serverKeyConfigured={}), uri={}",
                    API_KEY_HEADER, StringUtils.isNotBlank(configuredApiKey), request.getRequestURI());
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.apiKeyInvalid);
        }
        return true;
    }
}
