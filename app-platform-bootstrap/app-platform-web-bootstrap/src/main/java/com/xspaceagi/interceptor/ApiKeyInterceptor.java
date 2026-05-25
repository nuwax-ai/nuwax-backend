package com.xspaceagi.interceptor;

import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.dto.AgentDetailDto;
import com.xspaceagi.sandbox.SandboxRequestAttributes;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApiKeyApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.OpenApiDefinition;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.sdk.service.UserAccessKeyApiService;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.util.List;

@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private UserAccessKeyApiService userAccessKeyApiService;

    @Resource
    private UserApiKeyApplicationService userApiKeyApplicationService;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 请求处理完之后
     */
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object obj, Exception exc) throws Exception {
        RequestContext.remove();
    }

    /**
     * 请求处理完成
     */
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object obj, ModelAndView model) throws Exception {
    }

    /**
     * 请求处理之前
     */
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String originalRequestUri = getOriginalRequestUri(request);
        String authorization = request.getHeader("Authorization");
        if (authorization != null) {
            authorization = authorization.replaceFirst("Basic", "").replaceFirst("Bearer", "").trim();
        }
        //是否为ak校验
        if (authorization != null && (authorization.startsWith("ak-") || authorization.startsWith("ck-"))) {
            UserAccessKeyDto userAccessKeyDto = userAccessKeyApiService.queryAccessKey(authorization);
            if (userAccessKeyDto == null || (userAccessKeyDto.getStatus() != null && userAccessKeyDto.getStatus() != 1)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.apiKeyInvalid);
            }
            if (userAccessKeyDto.getExpire() != null && userAccessKeyDto.getExpire().getTime() < System.currentTimeMillis()) {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.apiKeyExpired);
            }
            if (!completeAuthContext(request, userAccessKeyDto)) {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.apiKeyAccessDenied);
            }
            return true;
        }
        if (originalRequestUri.startsWith("/api/v1/")) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.apiKeyMissing);
        }
        return true;
    }

    private boolean completeAuthContext(HttpServletRequest request, UserAccessKeyDto userAccessKeyDto) {
        String originalRequestUri = getOriginalRequestUri(request);
        UserDto userDto = userApplicationService.queryById(userAccessKeyDto.getUserId());
        if (userDto == null || userDto.getStatus() == User.Status.Disabled || userDto.getStatus() == User.Status.Deleted) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.apiKeyUserDisabled);
        }
        RequestContext.get().setUserId(userDto.getId());
        RequestContext.get().setUser(userDto);
        RequestContext.get().setUserContext(UserDto.convertToUserContext(userDto));
        RequestContext.get().setLogin(true);
        RequestContext.get().setUserAccessKey(userAccessKeyDto);
        if (userAccessKeyDto.getTargetType() == UserAccessKeyDto.AKTargetType.Agent) {
            if (originalRequestUri.startsWith("/api/v1/") || originalRequestUri.startsWith("/api/file/")) {
                TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
                if (tenantConfigDto.getAllowAgentApi() != null && tenantConfigDto.getAllowAgentApi().equals(YesOrNoEnum.N.getKey()) && userDto.getRole() != User.Role.Admin) {
                    throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.apiKeyAgentApiDisabled);
                }
                AgentDetailDto agentDetailDto = agentApplicationService.queryAgentDetail(Long.parseLong(userAccessKeyDto.getTargetId()), false);
                RequestContext.get().setAkTarget(agentDetailDto);
                return true;
            }
        }

        if (userAccessKeyDto.getTargetType() == UserAccessKeyDto.AKTargetType.OpenApi) {
            String path = request.getRequestURI();//path参考格式 /api/v1/user/add、/api/v1/user/1011（OpenApiDefinition的配置可能为/api/v1/user/:id，做匹配时要适配 :xx 为实际值）
            List<OpenApiDefinition> openApiDefinitions = userApiKeyApplicationService.queryOpenApiDefinitions(userDto.getId());
            OpenApiDefinition openApiDefinition = matchOpenApiDefinition(path, openApiDefinitions);// 从openApiDefinitions中过滤得到
            if (openApiDefinition == null) {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.apiKeyOpenApiNotFound);
            }

            request.setAttribute("currentAPI", openApiDefinition);
            request.setAttribute("userAccessKey", userAccessKeyDto);

            //普通用户不能使用管理员接口
            if (openApiDefinition.getRole() == User.Role.Admin && userDto.getRole() != User.Role.Admin) {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
            }

            List<UserAccessKeyDto.ApiConfig> apiConfigs = userAccessKeyDto.getConfig().getApiConfigs();
            // apiConfigs如果为空则有全部接口权限
            if (CollectionUtils.isNotEmpty(apiConfigs) && apiConfigs.stream().noneMatch(apiConfig -> openApiDefinition.getKey().equals(apiConfig.getKey()))) {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
            }
            userApiKeyApplicationService.requestLimitCheck(userDto.getId(), openApiDefinition);
            return true;
        }

        // 允许沙箱访问的接口范围
        if (userAccessKeyDto.getTargetType() == UserAccessKeyDto.AKTargetType.Sandbox && originalRequestUri.startsWith("/api/v1/4sandbox")) {
            return true;
        }
        return userAccessKeyDto.getTargetType() == UserAccessKeyDto.AKTargetType.Tenant;
    }

    public OpenApiDefinition matchOpenApiDefinition(String path, List<OpenApiDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return null;
        }

        for (OpenApiDefinition def : definitions) {
            // 当前节点匹配
            if (def.getPath() != null && def.getKey().equals("api.v1.chat.file") && pathMatcher.matchStart(convertPathPattern(def.getPath()), path)) {
                return def;
            }
            if (def.getPath() != null && pathMatcher.match(convertPathPattern(def.getPath()), path)) {
                return def;
            }
            // 递归匹配子节点
            OpenApiDefinition matched = matchOpenApiDefinition(path, def.getApiList());
            if (matched != null) {
                return matched;
            }
        }
        return null;
    }

    private static String convertPathPattern(String path) {
        if (path == null || !path.contains(":")) {
            return path;
        }
        return path.replaceAll(":([a-zA-Z_][a-zA-Z0-9_]*)", "{$1}");
    }

    private String getOriginalRequestUri(HttpServletRequest request) {
        Object val = request.getAttribute(SandboxRequestAttributes.ORIGINAL_REQUEST_URI);
        if (val instanceof String s && !s.isEmpty()) {
            return s;
        }
        return request.getRequestURI();
    }
}