package com.xspaceagi.custompage.application.service.impl;

import com.xspaceagi.agent.core.spec.utils.FileTypeUtils;
import com.xspaceagi.custompage.application.service.ICustomPageFileApplicationService;
import com.xspaceagi.custompage.domain.service.ICustomPageFileDomainService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.SpacePermissionException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Signal;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@Slf4j
@Service
public class CustomPageFileApplicationServiceImpl implements ICustomPageFileApplicationService {

    @Resource
    private ICustomPageFileDomainService customPageFileDomainService;

    @Override
    public ResponseEntity<StreamingResponseBody> getStaticFile(String requestPath, String staticPrefix, String targetPrefix, String logId, UserContext userContext) {
        String relativePath = "";

        int prefixIndex = requestPath.indexOf(staticPrefix);
        if (prefixIndex != -1) {
            relativePath = requestPath.substring(prefixIndex + staticPrefix.length());
        } else {
            log.error("[Web] Cannot extract relative Path from request, request Path={}, log Id={}", requestPath, logId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        if (relativePath.trim().isEmpty()) {
            log.error("[Web] relative Path is empty, request Path={}, log Id={}", requestPath, logId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // URL 解码
        String decodedPath = relativePath;
        try {
            decodedPath = URLDecoder.decode(relativePath, "UTF-8");
        } catch (Exception e) {
            log.warn("[Web] URL decode failed, relative Path={}, using raw path", relativePath, e);
        }
        final String finalRelativePath = decodedPath;

        log.info("[Web] Extracted log Id={}, relative Path={}", logId, finalRelativePath);

        try {
            Flux<DataBuffer> fileFlux = customPageFileDomainService.getStaticFile(targetPrefix, finalRelativePath, logId, userContext);

            // 在开始写入之前，先检查第一个信号来提前发现错误
            // 使用 share() 来共享 Flux，然后使用 materialize() 来检查第一个信号
            // 如果第一个信号是错误，就返回错误响应；否则继续使用原来的流式传输方式
            Flux<DataBuffer> sharedFlux = fileFlux.share();
            try {
                Signal<DataBuffer> firstSignal = sharedFlux.materialize().blockFirst();
                if (firstSignal != null && firstSignal.isOnError()) {
                    Throwable error = firstSignal.getThrowable();
                    if (error instanceof WebClientResponseException) {
                        WebClientResponseException e = (WebClientResponseException) error;
                        log.error("[Web] Failed to get static file, log Id={}, status={}", logId, e.getStatusCode());
                        HttpStatus status = e.getStatusCode() == org.springframework.http.HttpStatus.NOT_FOUND 
                                ? HttpStatus.NOT_FOUND 
                                : HttpStatus.INTERNAL_SERVER_ERROR;
                        return buildErrorResponse(status, "获取静态文件失败: " + e.getMessage(), logId);
                    } else if (error instanceof SpacePermissionException) {
                        SpacePermissionException e = (SpacePermissionException) error;
                        log.error("[Web] Insufficient permission for static file, log Id={}, {}", logId, e.getMessage());
                        return buildErrorResponse(HttpStatus.FORBIDDEN, e.getMessage(), logId, e.getCode());
                    } else {
                        log.error("[Web] Exception while accessing static file, log Id={}", logId, error);
                        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "访问静态文件失败: " + error.getMessage(), logId);
                    }
                }
            } catch (Exception e) {
                // materialize() 本身可能抛出异常，这种情况下也返回错误响应
                log.error("[Web] Exception while checking static file, log Id={}", logId, e);
                if (e instanceof WebClientResponseException) {
                    WebClientResponseException webEx = (WebClientResponseException) e;
                    HttpStatus status = webEx.getStatusCode() == org.springframework.http.HttpStatus.NOT_FOUND 
                            ? HttpStatus.NOT_FOUND 
                            : HttpStatus.INTERNAL_SERVER_ERROR;
                    return buildErrorResponse(status, "获取静态文件失败: " + webEx.getMessage(), logId);
                } else if (e instanceof SpacePermissionException) {
                    SpacePermissionException permEx = (SpacePermissionException) e;
                    return buildErrorResponse(HttpStatus.FORBIDDEN, permEx.getMessage(), logId, permEx.getCode());
                } else {
                    return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "访问静态文件失败: " + e.getMessage(), logId);
                }
            }

            // 如果第一个信号不是错误，使用共享的 Flux 继续流式传输
            // share() 确保多个订阅者可以共享同一个 Flux，不会丢失数据
            final Flux<DataBuffer> finalFileFlux = sharedFlux;

            // 根据文件扩展名设置正确的 Content-Type
            // 注意：CORS headers 由 HttpInterceptor 统一处理，这里不需要重复设置
            HttpHeaders headers = new HttpHeaders();
            MediaType contentType = FileTypeUtils.getContentTypeByFileName(finalRelativePath);
            headers.setContentType(contentType);

            // 创建 StreamingResponseBody 实现流式传输
            StreamingResponseBody streamingResponseBody = outputStream -> {
                try {
                    finalFileFlux
                            .doOnError(WebClientResponseException.class, e -> {
                                log.error("[Web] Failed to get static file, log Id={}, status={}, response Body={}", 
                                        logId, e.getStatusCode(), e.getResponseBodyAsString());
                            })
                            .doOnError(SpacePermissionException.class, e -> {
                                log.error("[Web] Insufficient permission for static file, log Id={}, {}", logId, e.getMessage());
                            })
                            .doOnError(Throwable.class, e -> {
                                log.error("[Web] Exception while accessing static file, log Id={}", logId, e);
                            })
                            .doOnNext(dataBuffer -> {
                                try {
                                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                    dataBuffer.read(bytes);
                                    outputStream.write(bytes);
                                    outputStream.flush();
                                } catch (IOException e) {
                                    log.error("[Web] Failed to write output stream, log Id={}", logId, e);
                                    throw new RuntimeException("Failed to write output stream", e);
                                } finally {
                                    DataBufferUtils.release(dataBuffer);
                                }
                            })
                            .doOnComplete(() -> {
                                log.info("[Web] File streaming completed, log Id={}, relative Path={}",
                                        logId, finalRelativePath);
                            })
                            .blockLast(); // 在 StreamingResponseBody 的回调中阻塞是正常的
                } catch (Exception e) {
                    log.error("[Web] File streaming failed, log Id={}", logId, e);
                    // 不再抛出异常，因为响应已经开始写入，无法改变状态码
                    // 错误已经在开始写入之前被检查和处理了
                }
            };

            return ResponseEntity.ok().headers(headers).body(streamingResponseBody);
        } catch (SpacePermissionException e) {
            log.error("[Web] Insufficient permission for static file, log Id={}, {}", logId, e.getMessage());
            return buildErrorResponse(HttpStatus.FORBIDDEN, e.getMessage(), logId, e.getCode());
        } catch (Exception e) {
            log.error("[Web] Failed to access static file, log Id={}", logId, e);
            return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "访问静态文件失败: " + e.getMessage(), logId);
        }
    }

    //构建错误响应（JSON格式）
    private ResponseEntity<StreamingResponseBody> buildErrorResponse(HttpStatus status, String message, String logId) {
        return buildErrorResponse(status, message, logId, "0001");
    }

    //构建错误响应（JSON格式）
    private ResponseEntity<StreamingResponseBody> buildErrorResponse(HttpStatus status, String message, String logId, String code) {
        String errorMessage = message != null ? message : "访问静态文件失败";
        
        // 移除 IP:端口 格式的敏感信息（如 192.168.1.34:60000）
        // 匹配 IPv4 地址:端口格式，包括可能的 http:// 或 https:// 前缀
        Pattern ipPortPattern = Pattern.compile("(https?://)?\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}:\\d+");
        errorMessage = ipPortPattern.matcher(errorMessage).replaceAll("");
        // 清理多余的空格
        errorMessage = errorMessage.trim().replaceAll("\\s+", " ");
        // 转义 JSON 字符串中的特殊字符
        String escapedMessage = errorMessage.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        String errorJson = "{\"code\":\"" + code + "\",\"message\":\"" + escapedMessage + "\"}";
        HttpHeaders errorHeaders = new HttpHeaders();
        errorHeaders.setContentType(MediaType.APPLICATION_JSON);
        StreamingResponseBody errorBody = outputStream -> {
            try {
                outputStream.write(errorJson.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            } catch (IOException ioException) {
                log.error("[Web] Failed to write error response, log Id={}", logId, ioException);
            }
        };
        return ResponseEntity.status(status).headers(errorHeaders).body(errorBody);
    }

}