package com.xspaceagi.domain.adaptor.impl;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.LogPlatformException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.domain.model.valueobj.AgentLogEntry;
import com.xspaceagi.domain.model.valueobj.AgentLogSearchParams;
import com.xspaceagi.domain.model.valueobj.AgentLogSearchRequest;
import com.xspaceagi.domain.model.valueobj.AgentLogSearchResult;
import com.xspaceagi.domain.model.valueobj.RustServiceResponse;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@Service
public class LogHttpClientAdapter {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;

    private final String baseUrl;
    @Resource
    private ObjectMapper objectMapper;

    @Autowired
    public LogHttpClientAdapter(LogHttpClientConfiguration config) {
        // 验证配置
        if (!StringUtils.hasText(config.getBaseUrl())) {
            String errorMsg = "日志服务配置错误：log-module.log.rust-service.base-url 不能为空，请检查配置文件";
            log.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }

        this.baseUrl = config.getBaseUrl();
        log.info("初始化日志 HTTP 客户端，服务地址: {}", this.baseUrl);

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                .writeTimeout(Duration.ofSeconds(config.getWriteTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(config.getReadTimeoutSeconds()))
                .build();
    }

    /**
     * 健康检查 - 直接使用同步API
     * GET /health
     */
    public boolean healthCheck() {
        Request request = new Request.Builder()
                .url(baseUrl + "/health")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (IOException e) {
            log.error("健康检查失败", e);
            return false;
        }
    }

    /**
     * 就绪检查 - 直接使用同步API
     * GET /ready
     */
    public boolean readyCheck() {
        Request request = new Request.Builder()
                .url(baseUrl + "/ready")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (IOException e) {
            log.error("就绪检查失败", e);
            return false;
        }
    }

    /**
     * 新增单个智能体日志 - 直接使用同步API
     * POST /api/agent/log/add
     */
    public boolean addAgentLog(AgentLogEntry logEntry) {
        try {
            String json = objectMapper.writeValueAsString(logEntry);
            RequestBody body = RequestBody.create(json, JSON);

            Request request = new Request.Builder()
                    .url(baseUrl + "/api/agent/log/add")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                boolean success = response.isSuccessful();
                if (!success) {
                    log.warn("新增单个智能体日志失败，状态码: {}, 响应: {}",
                            response.code(), response.body().string());
                }
                return success;
            }
        } catch (Exception e) {
            log.error("新增单个智能体日志失败", e);
            throw LogPlatformException.build(BizExceptionCodeEnum.logPlatformInsertFailed, e.getMessage());
        }
    }

    /**
     * 批量新增智能体日志 - 直接使用同步API
     * POST /api/agent/log/batch
     */
    public boolean batchAddAgentLogs(List<AgentLogEntry> logEntries) {
        try {
            String json = objectMapper.writeValueAsString(logEntries);
            RequestBody body = RequestBody.create(json, JSON);

            Request request = new Request.Builder()
                    .url(baseUrl + "/api/agent/log/batch")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                boolean success = response.isSuccessful();
                if (!success) {
                    log.warn("批量新增智能体日志失败，状态码: {}, 响应: {}",
                            response.code(), response.body().string());
                }
                return success;
            }
        } catch (Exception e) {
            log.error("批量新增智能体日志失败", e);
            throw LogPlatformException.build(BizExceptionCodeEnum.logPlatformBatchInsertFailed, e.getMessage());
        }
    }

    /**
     * 搜索智能体日志 - 直接使用同步API
     * POST /api/agent/log/search
     */
    public AgentLogSearchResult searchAgentLogs(AgentLogSearchRequest searchRequest) {
        try {
            // 确保 queryFilter 不为空，Rust 服务要求必须有这个字段
            if (searchRequest.getQueryFilter() == null) {
                searchRequest.setQueryFilter(new AgentLogSearchParams());
            }

            String json = objectMapper.writeValueAsString(searchRequest);
            log.debug("Sending log search request: {}", json);
            RequestBody body = RequestBody.create(json, JSON);

            Request request = new Request.Builder()
                    .url(baseUrl + "/api/agent/log/search")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    log.debug("Search response: {}", responseBody);

                    // 解析包装的响应
                    RustServiceResponse<AgentLogSearchResult> rustResponse = objectMapper.readValue(responseBody,
                            objectMapper.getTypeFactory().constructParametricType(
                                    RustServiceResponse.class, AgentLogSearchResult.class));

                    if (rustResponse.isSuccess()) {
                        return rustResponse.getData();
                    } else {
                        String errorMsg = String.format("服务返回错误：code=%s, message=%s",
                                rustResponse.getCode(), rustResponse.getMessage());
                        log.warn(errorMsg);
                        throw new LogPlatformException(BizExceptionCodeEnum.logPlatformSearchFailed.getCode(),
                                errorMsg);
                    }
                } else {
                    String errorBody = response.body().string();
                    log.warn("Agent log search failed, status: {}, body: {}", response.code(), errorBody);
                    throw new LogPlatformException(BizExceptionCodeEnum.logPlatformSearchFailed.getCode(),
                            "搜索失败，HTTP状态码: " + response.code() + ", 响应: " + errorBody);
                }
            }
        } catch (LogPlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("Agent log search failed", e);
            throw LogPlatformException.build(BizExceptionCodeEnum.logPlatformSearchFailed, e.getMessage());
        }
    }

    /**
     * 搜索智能体日志 - 直接使用同步API
     * POST /api/agent/log/search
     */
    public AgentLogSearchResult queryOneAgentLog(AgentLogSearchRequest searchRequest) {
        try {
            // 确保 queryFilter 不为空，Rust 服务要求必须有这个字段
            if (searchRequest.getQueryFilter() == null) {
                searchRequest.setQueryFilter(new AgentLogSearchParams());
            }

            String json = objectMapper.writeValueAsString(searchRequest);
            log.debug("Sending log search request: {}", json);
            RequestBody body = RequestBody.create(json, JSON);

            Request request = new Request.Builder()
                    .url(baseUrl + "/api/agent/log/detail")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    log.debug("Search response: {}", responseBody);

                    // 解析包装的响应
                    RustServiceResponse<AgentLogSearchResult> rustResponse = objectMapper.readValue(responseBody,
                            objectMapper.getTypeFactory().constructParametricType(
                                    RustServiceResponse.class, AgentLogSearchResult.class));

                    if (rustResponse.isSuccess()) {
                        return rustResponse.getData();
                    } else {
                        String errorMsg = String.format("服务返回错误：code=%s, message=%s",
                                rustResponse.getCode(), rustResponse.getMessage());
                        log.warn(errorMsg);
                        throw new LogPlatformException(BizExceptionCodeEnum.logPlatformSearchFailed.getCode(),
                                errorMsg);
                    }
                } else {
                    String errorBody = response.body().string();
                    log.warn("Agent log search failed, status: {}, body: {}", response.code(), errorBody);
                    throw new LogPlatformException(BizExceptionCodeEnum.logPlatformSearchFailed.getCode(),
                            "搜索失败，HTTP状态码: " + response.code() + ", 响应: " + errorBody);
                }
            }
        } catch (LogPlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("Agent log search failed", e);
            throw LogPlatformException.build(BizExceptionCodeEnum.logPlatformSearchFailed, e.getMessage());
        }
    }

    /**
     * 关闭资源
     */
    public void close() {
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }
}
