package com.xspaceagi.agent.core.application.service;

import com.xspaceagi.agent.core.adapter.application.IComputerFileApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ComputerFileInfo;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.domain.service.IComputerFileDomainService;
import com.xspaceagi.agent.core.infra.rpc.SandboxServerConfigService;
import com.xspaceagi.agent.core.infra.rpc.UserShareRpcService;
import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.agent.core.spec.utils.FileTypeUtils;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.sdk.service.dto.UserShareDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.SpacePermissionException;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Signal;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ComputerFileApplicationServiceImpl implements IComputerFileApplicationService {

    @Resource
    private IComputerFileDomainService computerFileDomainService;
    @Resource
    private SandboxServerConfigService sandboxServerConfigService;
    @Resource
    private UserShareRpcService userShareRpcService;
    @Resource
    private AuthService authService;

    @Override
    public Map<String, Object> getFileList(Long userId, Long cId, String proxyPath, UserContext userContext) {
        if (userId == null || userId <= 0) {
            log.error("[Web] userId 无效, userId={}", userId);
            return buildError("userId 无效");
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] cId 无效, cId={}", cId);
            return buildError("cId 无效");
        }
        try {
            return computerFileDomainService.getFileList(userId, cId, proxyPath, userContext);
        } catch (Exception e) {
            log.error("[Web] 查询文件列表异常, userId={}, cId={}", userId, cId, e);
            return buildError("查询文件列表失败");
        }
    }

    @Override
    public Map<String, Object> filesUpdate(Long userId, Long cId, List<ComputerFileInfo> files, UserContext userContext) {
        if (userId == null || userId <= 0) {
            log.error("[Web] userId 无效, userId={}", userId);
            return buildError("userId 无效");
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] cId 无效, cId={}", cId);
            return buildError("cId 无效");
        }
        if (files == null || files.isEmpty()) {
            log.error("[Web] files 无效, files为空");
            return buildError("files 无效");
        }
        try {
            return computerFileDomainService.filesUpdate(userId, cId, files, userContext);
        } catch (Exception e) {
            log.error("[Web] 更新用户文件列表异常, userId={}, cId={}", userId, cId, e);
            return buildError("更新用户文件列表失败,请检查文件名是否重复");
        }
    }

    @Override
    public Map<String, Object> uploadFile(Long userId, Long cId, String filePath, MultipartFile file, UserContext userContext) {
        if (userId == null || userId <= 0) {
            log.error("[Web] userId 无效, userId={}", userId);
            return buildError("userId 无效");
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] cId 无效, cId={}", cId);
            return buildError("cId 无效");
        }
        if (filePath == null || filePath.trim().isEmpty()) {
            log.error("[Web] filePath 无效, filePath={}", filePath);
            return buildError("filePath 无效");
        }
        if (file == null || file.isEmpty()) {
            log.error("[Web] file 无效, file为空");
            return buildError("file 无效");
        }
        try {
            return computerFileDomainService.uploadFile(userId, cId, filePath, file, userContext);
        } catch (Exception e) {
            log.error("[Web] 上传用户文件异常, userId={}, cId={}, filePath={}", userId, cId, filePath, e);
            return buildError("上传用户文件失败");
        }
    }

    @Override
    public Map<String, Object> uploadFiles(Long userId, Long cId, List<String> filePaths, List<MultipartFile> files, UserContext userContext) {
        if (userId == null || userId <= 0) {
            log.error("[Web] userId 无效, userId={}", userId);
            return buildError("userId 无效");
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] cId 无效, cId={}", cId);
            return buildError("cId 无效");
        }
        if (filePaths == null || filePaths.isEmpty()) {
            log.error("[Web] filePaths 无效, filePaths 为空");
            return buildError("filePaths 无效");
        }
        if (files == null || files.isEmpty()) {
            log.error("[Web] files 无效, files 为空");
            return buildError("files 无效");
        }
        if (filePaths.size() != files.size()) {
            log.error("[Web] filePaths 与 files 数量不一致, filePathsSize={}, filesSize={}", filePaths.size(), files.size());
            return buildError("filePaths 与 files 数量不一致");
        }
        try {
            return computerFileDomainService.uploadFiles(userId, cId, filePaths, files, userContext);
        } catch (Exception e) {
            log.error("[Web] 批量上传用户文件异常, userId={}, cId={}", userId, cId, e);
            return buildError("批量上传用户文件失败");
        }
    }

    @Override
    public ResponseEntity<StreamingResponseBody> downloadAllFiles(Long userId, Long cId, UserContext userContext) {
        if (userId == null || userId <= 0) {
            log.error("[Web] userId 无效, userId={}", userId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] cId 无效, cId={}", cId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        String logId = userId + "_" + cId;
        log.info("[Web] 下载全部文件, logId={}, userId={}, cId={}", logId, userId, cId);

        try {
            Flux<DataBuffer> fileFlux = computerFileDomainService.downloadAllFiles(userId, cId, logId, userContext);

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
                        log.error("[Web] 下载全部文件失败，logId={}, status={}", logId, e.getStatusCode());
                        HttpStatus status = e.getStatusCode() == org.springframework.http.HttpStatus.NOT_FOUND
                                ? HttpStatus.NOT_FOUND
                                : HttpStatus.INTERNAL_SERVER_ERROR;
                        return buildErrorResponse(status, "下载全部文件失败: " + e.getMessage(), logId);
                    } else if (error instanceof SpacePermissionException) {
                        SpacePermissionException e = (SpacePermissionException) error;
                        log.error("[Web] 下载全部文件权限不足，logId={}, {}", logId, e.getMessage());
                        return buildErrorResponse(HttpStatus.FORBIDDEN, e.getMessage(), logId, e.getCode());
                    } else {
                        log.error("[Web] 下载全部文件异常，logId={}", logId, error);
                        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "下载全部文件失败: " + error.getMessage(), logId);
                    }
                }
            } catch (Exception e) {
                // materialize() 本身可能抛出异常，这种情况下也返回错误响应
                log.error("[Web] 检查下载全部文件时发生异常，logId={}", logId, e);
                if (e instanceof WebClientResponseException) {
                    WebClientResponseException webEx = (WebClientResponseException) e;
                    HttpStatus status = webEx.getStatusCode() == org.springframework.http.HttpStatus.NOT_FOUND
                            ? HttpStatus.NOT_FOUND
                            : HttpStatus.INTERNAL_SERVER_ERROR;
                    return buildErrorResponse(status, "下载全部文件失败: " + webEx.getMessage(), logId);
                } else if (e instanceof SpacePermissionException) {
                    SpacePermissionException permEx = (SpacePermissionException) e;
                    return buildErrorResponse(HttpStatus.FORBIDDEN, permEx.getMessage(), logId, permEx.getCode());
                } else {
                    return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "下载全部文件失败: " + e.getMessage(), logId);
                }
            }

            // 如果第一个信号不是错误，使用共享的 Flux 继续流式传输
            // share() 确保多个订阅者可以共享同一个 Flux，不会丢失数据
            final Flux<DataBuffer> finalFileFlux = sharedFlux;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            String zipFileName = "files-" + userId + "-" + cId + ".zip";
            String encodedName = URLEncoder.encode(zipFileName, StandardCharsets.UTF_8);
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + encodedName + "\"; filename*=UTF-8''" + encodedName);

            StreamingResponseBody streamingResponseBody = outputStream -> {
                try {
                    finalFileFlux
                            .doOnError(WebClientResponseException.class, e -> {
                                log.error("[Web] 下载全部文件失败，logId={}, status={}, responseBody={}",
                                        logId, e.getStatusCode(), e.getResponseBodyAsString());
                            })
                            .doOnError(SpacePermissionException.class, e -> {
                                log.error("[Web] 下载全部文件权限不足，logId={}, {}", logId, e.getMessage());
                            })
                            .doOnError(Throwable.class, e -> {
                                log.error("[Web] 下载全部文件异常，logId={}", logId, e);
                            })
                            .doOnNext(dataBuffer -> {
                                try {
                                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                    dataBuffer.read(bytes);
                                    outputStream.write(bytes);
                                    outputStream.flush();
                                } catch (IOException e) {
                                    log.error("[Web] 写入 zip 输出流失败, logId={}", logId, e);
                                    throw new RuntimeException("写入输出流失败", e);
                                } finally {
                                    DataBufferUtils.release(dataBuffer);
                                }
                            })
                            .doOnComplete(() -> log.info("[Web] 全部文件 zip 流式传输完成, logId={}", logId))
                            .blockLast();
                } catch (Exception e) {
                    log.error("[Web] 流式传输全部文件 zip 失败, logId={}", logId, e);
                    // 不再抛出异常，因为响应已经开始写入，无法改变状态码
                    // 错误已经在开始写入之前被检查和处理了
                }
            };

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(streamingResponseBody);
        } catch (SpacePermissionException e) {
            log.error("[Web] 下载全部文件权限不足，logId={}, {}", logId, e.getMessage());
            return buildErrorResponse(HttpStatus.FORBIDDEN, e.getMessage(), logId, e.getCode());
        } catch (Exception e) {
            log.error("[Web] 下载全部文件失败，logId={}", logId, e);
            return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "下载全部文件失败: " + e.getMessage(), logId);
        }
    }

    @Override
    public ResponseEntity<StreamingResponseBody> getStaticFile(Long cId, HttpServletRequest request) {
        ConversationDto currentConversation = getConversation(cId);
        AuthResult authResult = staticFileAuth(currentConversation, cId, request);
        if (authResult.getRedirectUrl() != null) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(authResult.getRedirectUrl())).build();
        }
        Long conversationUserId = authResult.getUserId();

        log.info("[Web] 访问静态文件，conversationUserId={}, cId={}, ", conversationUserId, cId);
        String staticPrefix = "/api/computer/static/" + cId + "/";
        String targetPrefix = "/computer/static/" + conversationUserId + "/" + cId + "/";
        String logId = conversationUserId + "_" + cId;

        String requestPath = request.getRequestURI();
        String relativePath = "";

        int prefixIndex = requestPath.indexOf(staticPrefix);
        if (prefixIndex != -1) {
            relativePath = requestPath.substring(prefixIndex + staticPrefix.length());
        } else {
            log.error("[Web] 无法从请求路径中提取 relativePath, requestPath={}, logId={}", requestPath, logId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        if (relativePath.trim().isEmpty()) {
            log.error("[Web] relativePath 为空, requestPath={}, logId={}", requestPath, logId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // URL 解码
        String decodedPath = relativePath;
        try {
            decodedPath = URLDecoder.decode(relativePath, "UTF-8");
        } catch (Exception e) {
            log.warn("[Web] URL 解码失败, relativePath={}, 使用原始路径", relativePath, e);
        }
        final String finalRelativePath = decodedPath;

        log.info("[Web] 提取的 logId={}, relativePath={}", logId, finalRelativePath);

        try {
            Flux<DataBuffer> fileFlux = computerFileDomainService.getStaticFile(cId, targetPrefix, finalRelativePath, logId);

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
                        log.error("[Web] 获取静态文件失败，logId={}, status={}", logId, e.getStatusCode());
                        HttpStatus status = e.getStatusCode() == org.springframework.http.HttpStatus.NOT_FOUND
                                ? HttpStatus.NOT_FOUND
                                : HttpStatus.INTERNAL_SERVER_ERROR;
                        return buildErrorResponse(status, e.getMessage(), logId);
                    } else if (error instanceof SpacePermissionException) {
                        SpacePermissionException e = (SpacePermissionException) error;
                        log.error("[Web] 访问静态文件权限不足，logId={}, {}", logId, e.getMessage());
                        return buildErrorResponse(HttpStatus.FORBIDDEN, e.getMessage(), logId, e.getCode());
                    } else {
                        log.error("[Web] 访问静态文件异常，logId={}", logId, error);
                        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "访问静态文件失败: " + error.getMessage(), logId);
                    }
                }
            } catch (Exception e) {
                // materialize() 本身可能抛出异常，这种情况下也返回错误响应
                log.error("[Web] 检查静态文件时发生异常，logId={}", logId, e);
                if (e instanceof WebClientResponseException) {
                    WebClientResponseException webEx = (WebClientResponseException) e;
                    HttpStatus status = webEx.getStatusCode() == org.springframework.http.HttpStatus.NOT_FOUND
                            ? HttpStatus.NOT_FOUND
                            : HttpStatus.INTERNAL_SERVER_ERROR;
                    return buildErrorResponse(status, webEx.getMessage(), logId);
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

            // 如果从 ticket 获取了 token，设置 Cookie 以便后续请求使用
            if (authResult.getToken() != null) {
                headers.add("Set-Cookie", "ticket=" + authResult.getToken() + "; Path=/; HttpOnly; SameSite=None; Secure");
                log.info("设置 Cookie ticket, logId={}", logId);
            }
            if (authResult.getUserShare() != null) {
                String skCookieKey = getSkCookieKey(conversationUserId, cId);
                UserShareDto userShare = authResult.getUserShare();
                String cookieValue = skCookieKey + userShare.getShareKey() + "; Path=/; SameSite=None; Secure";
                Date expire = userShare.getExpire();
                if (expire != null) {
                    // 计算从当前时间到过期时间的秒数
                    long maxAge = (expire.getTime() - System.currentTimeMillis()) / 1000;
                    if (maxAge > 0) {
                        cookieValue += "; Max-Age=" + maxAge;
                    } else {
                        // 如果已经过期，设置 Max-Age=0 立即删除 cookie
                        cookieValue += "; Max-Age=0";
                    }
                }
                headers.add("Set-Cookie", cookieValue);
            }

            // 创建 StreamingResponseBody 实现流式传输
            StreamingResponseBody streamingResponseBody = outputStream -> {
                try {
                    finalFileFlux
                            .doOnError(WebClientResponseException.class, e -> {
                                log.error("[Web] 获取静态文件失败，logId={}, status={}, responseBody={}",
                                        logId, e.getStatusCode(), e.getResponseBodyAsString());
                            })
                            .doOnError(SpacePermissionException.class, e -> {
                                log.error("[Web] 访问静态文件权限不足，logId={}, {}", logId, e.getMessage());
                            })
                            .doOnError(Throwable.class, e -> {
                                log.error("[Web] 访问静态文件异常，logId={}", logId, e);
                            })
                            .doOnNext(dataBuffer -> {
                                try {
                                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                    dataBuffer.read(bytes);
                                    outputStream.write(bytes);
                                    outputStream.flush();
                                } catch (IOException e) {
                                    log.error("[Web] 写入输出流失败, logId={}", logId, e);
                                    throw new RuntimeException("写入输出流失败", e);
                                } finally {
                                    DataBufferUtils.release(dataBuffer);
                                }
                            })
                            .doOnComplete(() -> {
                                log.info("[Web] 文件流式传输完成, logId={}, relativePath={}", logId, finalRelativePath);
                            })
                            .blockLast(); // 在 StreamingResponseBody 的回调中阻塞是正常的
                } catch (Exception e) {
                    log.error("[Web] 流式传输文件失败, logId={}", logId, e);
                    // 不再抛出异常，因为响应已经开始写入，无法改变状态码
                    // 错误已经在开始写入之前被检查和处理了
                }
            };

            return ResponseEntity.ok().headers(headers).body(streamingResponseBody);
        } catch (SpacePermissionException e) {
            log.error("[Web] 访问静态文件权限不足，logId={}, {}", logId, e.getMessage());
            return buildErrorResponse(HttpStatus.FORBIDDEN, e.getMessage(), logId, e.getCode());
        } catch (Exception e) {
            log.error("[Web] 访问静态文件失败，logId={}", logId, e);
            return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "访问静态文件失败: " + e.getMessage(), logId);
        }
    }

    private ConversationDto getConversation(Long cId) {
        SandboxServerConfig.SandboxServer sandboxServer = null;
        try {
            sandboxServer = sandboxServerConfigService.selectServer(cId);
        } catch (Exception e) {
            throw new BizException(e.getMessage());
        }
        if (sandboxServer == null) {
            throw new BizException("未找到文件服务器");
        }
        ConversationDto currentConversation = sandboxServer.getCurrentConversation();
        if (currentConversation == null) {
            throw new BizException("未找到当前会话");
        }
        return currentConversation;
    }

    /**
     * 认证结果，包含用户ID和token（如果是从ticket获取的）。
     * 当 redirectUrl 非空时表示需要重定向到登录页，此时 userId 为 null。
     */
    @Getter
    @AllArgsConstructor
    private static class AuthResult {
        private final Long userId;
        private final String token;
        private final UserShareDto userShare;
        private final String redirectUrl;
    }

    // sk鉴权
    private UserShareDto authWithSk(ConversationDto currentConversation, Long cId, HttpServletRequest request) {
        Long conversationUserId = currentConversation.getUserId();
        if (conversationUserId == null) {
            return null;
        }

        // 1. 优先从请求参数 sk 获取
        String sk = request.getParameter("sk");

        if (StringUtils.isBlank(sk)) {
            // 2. 从 Cookie 解析 sk
            String cookieHeader = request.getHeader("Cookie");
            if (StringUtils.isBlank(cookieHeader)) {
                return null;
            }
            String skCookieKey = getSkCookieKey(conversationUserId, cId);
            int start = cookieHeader.indexOf(skCookieKey);
            if (start < 0) {
                return null;
            }
            int valueStart = start + skCookieKey.length();
            int end = cookieHeader.indexOf(";", valueStart);
            sk = end > 0 ? cookieHeader.substring(valueStart, end) : cookieHeader.substring(valueStart);

        }
        if (StringUtils.isBlank(sk)) {
            return null;
        }

        UserShareDto userShare = userShareRpcService.getUserShare(sk, true);
        if (userShare != null
                && userShare.getType() == UserShareDto.UserShareType.CONVERSATION
                && conversationUserId.equals(userShare.getUserId())
                && userShare.getTargetId().equals(cId.toString())) {
            return userShare;
        }
        return null;
    }

    private AuthResult staticFileAuth(ConversationDto currentConversation, Long cId, HttpServletRequest request) {
        Long conversationUserId = currentConversation.getUserId();

        UserShareDto userShare = authWithSk(currentConversation, cId, request);
        if (userShare != null) {
            return new AuthResult(conversationUserId, null, userShare, null);
        }

        Long currentUserId = RequestContext.get().getUserId();
        if (currentUserId == null) {
            String ticket = request.getParameter("_ticket");
            if (StringUtils.isNotBlank(ticket) && authService != null) {
                try {
                    String token = authService.getTokenByTicket(ticket);
                    if (StringUtils.isNotBlank(token)) {
                        UserDto userDto = authService.getLoginUserInfo(token);
                        if (userDto != null) {
                            log.info("从 URI ticket 获取用户信息成功, userId={}", userDto.getId());
                            currentUserId = userDto.getId();
                            if (!currentUserId.equals(conversationUserId)) {
                                throw new BizException("无权访问当前资源");
                            }
                            return new AuthResult(conversationUserId, token, null, null);
                        } else {
                            log.warn("从 URI ticket 获取用户信息失败, token={}", token);
                        }
                    }
                } catch (BizException e) {
                    // 业务异常（如权限不足）直接抛出，不捕获
                    throw e;
                } catch (Exception e) {
                    log.warn("从 URI ticket 获取用户信息失败, ticket={}", ticket, e);
                }
            }
            // 无法获取用户信息，跳转到登录页
            StringBuffer requestURL = request.getRequestURL();
            String queryString = request.getQueryString();
            String referer = requestURL.toString();
            if (queryString != null && !queryString.isEmpty()) {
                referer += "?" + queryString;
            }
            String redirectUrl = "/login?redirect=" + URLEncoder.encode(referer, StandardCharsets.UTF_8);
            return new AuthResult(null, null, null, redirectUrl);
        }

        if (!currentUserId.equals(conversationUserId)) {
            throw new BizException("无权访问当前资源");
        }
        return new AuthResult(conversationUserId, null, null, null);
    }

    private Map<String, Object> buildError(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("code", "0001");
        result.put("message", message);
        return result;
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
                log.error("[Web] 写入错误响应失败, logId={}", logId, ioException);
            }
        };
        return ResponseEntity.status(status).headers(errorHeaders).body(errorBody);
    }

    private String getSkCookieKey(Long conversationUserId, Long cId) {
        return "static_sk_" + conversationUserId + "_" + cId + "=";
    }
}