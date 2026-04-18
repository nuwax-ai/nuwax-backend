package com.xspaceagi.interceptor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.xspaceagi.log.sdk.service.ILogRpcService;
import com.xspaceagi.log.sdk.vo.LogDocument;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.infra.dao.entity.OpenApiDefinition;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 请求参数记录
 *
 * @apiNote http前端后请求参数记录
 */
@Slf4j
@Component
@ControllerAdvice
public class HttpInterceptor implements HandlerInterceptor, ResponseBodyAdvice, RequestBodyAdvice {

    @Value("${access.control.allow-origin}")
    private String accessControlAllowOrigin;

    @Autowired
    private jakarta.servlet.http.HttpServletRequest request;

    @Resource
    private ILogRpcService iLogRpcService;

    @Override
    public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                             jakarta.servlet.http.HttpServletResponse response, Object handler) {
        String origin = request.getHeader("Origin");
        // 如果配置是 "*" 或者 origin 包含配置的值，则允许跨域
        boolean allowOrigin = StringUtils.isNotBlank(origin) &&
                ("*".equals(accessControlAllowOrigin) || origin.contains(accessControlAllowOrigin));
        if (allowOrigin) {
            response.setHeader("Vary", "Origin");
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Methods", "HEAD,GET,POST,PUT,DELETE,OPTIONS");
            response.setHeader("Access-Control-Allow-Headers",
                    "Origin, X-Requested-With, Content-Type, Accept, Authorization, Cache-Control, Fragment");
            response.setHeader("Access-Control-Allow-Credentials", "true");
        }
        if (request.getMethod().equals("OPTIONS")) {
            return true;
        }
        String uri = request.getRequestURI();
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        long reqBeginTime = Instant.now().toEpochMilli();
        // 请求开始时间
        request.setAttribute("reqBeginTime", reqBeginTime);
        HandlerMethod handlerMethod = (HandlerMethod) handler;
        Method method = handlerMethod.getMethod();
        request.setAttribute("methodName", method.getDeclaringClass().getName() + "." + method.getName());
        log.info("Request URI {}, X-Client-Type {}", uri, request.getHeader("X-Client-Type"));
        return true;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class selectedConverterType, ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof ReqResult httpResult)) {
            return body;
        }
        ServletServerHttpRequest servletServerHttpRequest = (ServletServerHttpRequest) request;
        jakarta.servlet.http.HttpServletRequest httpServletRequest = servletServerHttpRequest.getServletRequest();
        Map<String, String[]> params = httpServletRequest.getParameterMap();
        Map<String, Object> req = new HashMap<>();
        req.put("parameter", params);
        Object reqBody = this.request.getAttribute("requestBody");
        if (null != reqBody) {
            req.put("body", reqBody);
        }
        RequestContext<Object> objectRequestContext = RequestContext.get();
        if (null != objectRequestContext && objectRequestContext.getUser() != null && objectRequestContext.getUser() instanceof UserDto userDto) {
            Map<String, Object> user = new HashMap<>();
            user.put("tenantId", userDto.getTenantId());
            user.put("userId", userDto.getId());
            user.put("userName", userDto.getUserName() == null ? userDto.getNickName() : userDto.getUserName());
            req.put("user", user);
        }
        long reqBeginTime = (long) httpServletRequest.getAttribute("reqBeginTime");
        String methodName = (String) httpServletRequest.getAttribute("methodName");

        OpenApiDefinition openApiDefinition = (OpenApiDefinition) httpServletRequest.getAttribute("currentAPI");
        UserAccessKeyDto userAccessKey = (UserAccessKeyDto) httpServletRequest.getAttribute("userAccessKey");
        try {
            if (log.isInfoEnabled() || openApiDefinition != null) {
                // 启用 LargeObject 特性，支持序列化包含大文件内容的请求和响应体
                String requestBodyStr = JSON.toJSONString(req, JSONWriter.Feature.LargeObject);
                String responseBody = JSON.toJSONString(body, JSONWriter.Feature.LargeObject);
                if (openApiDefinition != null) {
                    requestBodyStr = httpServletRequest.getMethod() + " " + httpServletRequest.getRequestURI() + "\n" + requestBodyStr;
                }
                buildAndSendLogDocument(openApiDefinition, userAccessKey, requestBodyStr, responseBody, reqBeginTime, httpResult);
                log.info("HTTP API call log\nService {}\nRequest {}\nResponse {}\nElapsed {}ms", methodName, requestBodyStr.length() > 1024 * 8 ? requestBodyStr.substring(0, 1024 * 8) : requestBodyStr, responseBody.length() > 1024 * 8 ? responseBody.substring(0, 1024 * 8) : responseBody
                        , System.currentTimeMillis() - reqBeginTime);
            }
            httpResult.setTid(MDC.get("tid"));
            return httpResult;
        } catch (Exception e) {
            log.error("Failed to record HTTP access log", e);
            // 使用 LargeObject 特性序列化请求参数，避免大文件内容导致序列化失败
            String paramsStr = JSON.toJSONString(params, JSONWriter.Feature.LargeObject);
            log.error("HTTP API call log\nService {}\nRequest {}\nError {}\nElapsed {}ms", methodName, paramsStr.length() > 1024 * 8 ? paramsStr.substring(0, 1024 * 8) : paramsStr,
                    e.getMessage(), System.currentTimeMillis() - reqBeginTime);
            throw e;
        }
    }

    private void buildAndSendLogDocument(OpenApiDefinition openApiDefinition, UserAccessKeyDto userAccessKey, String requestBodyStr, String responseBody, Long reqBeginTime, ReqResult httpResult) {
        if (openApiDefinition == null) {
            return;
        }
        UserDto user = (UserDto) RequestContext.get().getUser();
        LogDocument logDocument = LogDocument.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .tenantId(RequestContext.get().getTenantId())
                .spaceId(-1L)
                .userId(RequestContext.get().getUserId())
                .userName(user.getUserName())
                .targetType("ApiKey")
                .targetName(userAccessKey.getName())
                .targetId(userAccessKey.getId().toString())
                .input(requestBodyStr)
                .output(responseBody)
                .requestStartTime(reqBeginTime)
                .requestEndTime(System.currentTimeMillis())
                .resultCode(httpResult.getCode())
                .resultMsg(httpResult.getMessage())
                .conversationId(openApiDefinition.getPath())
                .requestId(MDC.get("tid"))
                .createTime(System.currentTimeMillis())
                .from("ApiKey")
                .build();
        iLogRpcService.bulkIndex(List.of(logDocument));
    }

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) {
        return inputMessage;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
                                Class<? extends HttpMessageConverter<?>> converterType) {
        request.setAttribute("requestBody", body);
        return body;
    }

    @Override
    public Object handleEmptyBody(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                  Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return body;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
            throws Exception {
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
    }
}
