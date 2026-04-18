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
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
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
            log.error("[Web] Invalid userId, userId={}", userId);
            return buildError("userId is invalid");
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] Invalid cId, cId={}", cId);
            return buildError("cId is invalid");
        }
        try {
            return computerFileDomainService.getFileList(userId, cId, proxyPath, userContext);
        } catch (Exception e) {
            log.error("[Web] Exception querying file list, userId={}, cId={}", userId, cId, e);
            return buildError("Failed to query file list");
        }
    }

    @Override
    public Map<String, Object> filesUpdate(Long userId, Long cId, List<ComputerFileInfo> files, UserContext userContext) {
        if (userId == null || userId <= 0) {
            log.error("[Web] Invalid userId, userId={}", userId);
            return buildError("userId is invalid");
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] Invalid cId, cId={}", cId);
            return buildError("cId is invalid");
        }
        if (files == null || files.isEmpty()) {
            log.error("[Web] files is invalid, files is empty");
            return buildError("files is invalid");
        }
        try {
            return computerFileDomainService.filesUpdate(userId, cId, files, userContext);
        } catch (Exception e) {
            log.error("[Web] Exception updating user file list, userId={}, cId={}", userId, cId, e);
            return buildError("Failed to update user file list, please check if filename is duplicated");
        }
    }

    @Override
    public Map<String, Object> uploadFile(Long userId, Long cId, String filePath, MultipartFile file, UserContext userContext) {
        if (userId == null || userId <= 0) {
            log.error("[Web] Invalid userId, userId={}", userId);
            return buildError("userId is invalid");
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] Invalid cId, cId={}", cId);
            return buildError("cId is invalid");
        }
        if (filePath == null || filePath.trim().isEmpty()) {
            log.error("[Web] filePath is invalid, filePath={}", filePath);
            return buildError("filePath is invalid");
        }
        if (file == null || file.isEmpty()) {
            log.error("[Web] file is invalid, file is empty");
            return buildError("file is invalid");
        }
        try {
            return computerFileDomainService.uploadFile(userId, cId, filePath, file, userContext);
        } catch (Exception e) {
            log.error("[Web] Exception uploading user file, userId={}, cId={}, filePath={}", userId, cId, filePath, e);
            return buildError("Failed to upload user file");
        }
    }

    @Override
    public Map<String, Object> uploadFiles(Long userId, Long cId, List<String> filePaths, List<MultipartFile> files, UserContext userContext) {
        if (userId == null || userId <= 0) {
            log.error("[Web] Invalid userId, userId={}", userId);
            return buildError("userId is invalid");
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] Invalid cId, cId={}", cId);
            return buildError("cId is invalid");
        }
        if (filePaths == null || filePaths.isEmpty()) {
            log.error("[Web] filePaths is invalid, filePaths is empty");
            return buildError("filePaths is invalid");
        }
        if (files == null || files.isEmpty()) {
            log.error("[Web] files is invalid, files is empty");
            return buildError("files is invalid");
        }
        if (filePaths.size() != files.size()) {
            log.error("[Web] filePaths and files count mismatch, filePathsSize={}, filesSize={}", filePaths.size(), files.size());
            return buildError("filePaths and files count mismatch");
        }
        try {
            return computerFileDomainService.uploadFiles(userId, cId, filePaths, files, userContext);
        } catch (Exception e) {
            log.error("[Web] Exception batch uploading user files, userId={}, cId={}", userId, cId, e);
            return buildError("Failed to batch upload user files");
        }
    }

    @Override
    public ResponseEntity<StreamingResponseBody> downloadAllFiles(Long userId, Long cId, UserContext userContext) {
        if (userId == null || userId <= 0) {
            log.error("[Web] Invalid userId, userId={}", userId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (cId == null || cId <= 0) {
            log.error("[Web] Invalid cId, cId={}", cId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        String logId = userId + "_" + cId;
        log.info("[Web] Download all files, logId={}, userId={}, cId={}", logId, userId, cId);

        try {
            Flux<DataBuffer> fileFlux = computerFileDomainService.downloadAllFiles(userId, cId, logId, userContext);

            // Check the first signal before starting to write to detect errors early
            // Use share() to share Flux, then use materialize() to check the first signal
            // If the first signal is an error, return an error response; otherwise continue with the original streaming method
            Flux<DataBuffer> sharedFlux = fileFlux.share();
            try {
                Signal<DataBuffer> firstSignal = sharedFlux.materialize().blockFirst();
                if (firstSignal != null && firstSignal.isOnError()) {
                    Throwable error = firstSignal.getThrowable();
                    if (error instanceof WebClientResponseException) {
                        WebClientResponseException e = (WebClientResponseException) error;
                        log.error("[Web] Failed to download all files, logId={}, status={}", logId, e.getStatusCode());
                        HttpStatus status = e.getStatusCode() == org.springframework.http.HttpStatus.NOT_FOUND
                                ? HttpStatus.NOT_FOUND
                                : HttpStatus.INTERNAL_SERVER_ERROR;
                        return buildErrorResponse(status, "Failed to download all files: " + e.getMessage(), logId);
                    } else if (error instanceof SpacePermissionException) {
                        SpacePermissionException e = (SpacePermissionException) error;
                        log.error("[Web] Insufficient permission to download all files, logId={}, {}", logId, e.getMessage());
                        return buildErrorResponse(HttpStatus.FORBIDDEN, e.getMessage(), logId, e.getCode());
                    } else {
                        log.error("[Web] Exception downloading all files, logId={}", logId, error);
                        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to download all files: " + error.getMessage(), logId);
                    }
                }
            } catch (Exception e) {
                // materialize() itself may throw exceptions, in which case also return an error response
                log.error("[Web] Exception while checking download all files, logId={}", logId, e);
                if (e instanceof WebClientResponseException) {
                    WebClientResponseException webEx = (WebClientResponseException) e;
                    HttpStatus status = webEx.getStatusCode() == org.springframework.http.HttpStatus.NOT_FOUND
                            ? HttpStatus.NOT_FOUND
                            : HttpStatus.INTERNAL_SERVER_ERROR;
                    return buildErrorResponse(status, "Failed to download all files: " + webEx.getMessage(), logId);
                } else if (e instanceof SpacePermissionException) {
                    SpacePermissionException permEx = (SpacePermissionException) e;
                    return buildErrorResponse(HttpStatus.FORBIDDEN, permEx.getMessage(), logId, permEx.getCode());
                } else {
                    return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to download all files: " + e.getMessage(), logId);
                }
            }

            // If the first signal is not an error, use the shared Flux to continue streaming
            // share() ensures that multiple subscribers can share the same Flux without losing data
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
                                log.error("[Web] Failed to download all files, logId={}, status={}, responseBody={}",
                                        logId, e.getStatusCode(), e.getResponseBodyAsString());
                            })
                            .doOnError(SpacePermissionException.class, e -> {
                                log.error("[Web] Insufficient permission to download all files, logId={}, {}", logId, e.getMessage());
                            })
                            .doOnError(Throwable.class, e -> {
                                log.error("[Web] Exception downloading all files, logId={}", logId, e);
                            })
                            .doOnNext(dataBuffer -> {
                                try {
                                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                    dataBuffer.read(bytes);
                                    outputStream.write(bytes);
                                    outputStream.flush();
                                } catch (IOException e) {
                                    log.error("[Web] Failed to write zip output stream, logId={}", logId, e);
                                    throw new RuntimeException("Failed to write output stream", e);
                                } finally {
                                    DataBufferUtils.release(dataBuffer);
                                }
                            })
                            .doOnComplete(() -> log.info("[Web] All files zip streaming completed, logId={}", logId))
                            .blockLast();
                } catch (Exception e) {
                    log.error("[Web] Streaming all files zip failed, logId={}", logId, e);
                    // No longer throw exception, because the response has already started writing and cannot change the status code
                    // Errors have been checked and handled before starting to write
                }
            };

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(streamingResponseBody);
        } catch (SpacePermissionException e) {
            log.error("[Web] Insufficient permission to download all files, logId={}, {}", logId, e.getMessage());
            return buildErrorResponse(HttpStatus.FORBIDDEN, e.getMessage(), logId, e.getCode());
        } catch (Exception e) {
            log.error("[Web] Failed to download all files, logId={}", logId, e);
            return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to download all files: " + e.getMessage(), logId);
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

        log.info("[Web] Access static file, conversationUserId={}, cId={}, ", conversationUserId, cId);
        String staticPrefix = "/api/computer/static/" + cId + "/";
        String staticPrefixForApi = "/api/v1/chat/" + cId + "/file/";
        String targetPrefix = "/computer/static/" + conversationUserId + "/" + cId + "/";
        String logId = conversationUserId + "_" + cId;

        String requestPath = request.getRequestURI();
        String relativePath = "";

        int prefixIndex = requestPath.startsWith(staticPrefixForApi) ? requestPath.indexOf(staticPrefixForApi) : requestPath.indexOf(staticPrefix);
        int prefixLength = requestPath.startsWith(staticPrefixForApi) ? staticPrefixForApi.length() : staticPrefix.length();
        if (prefixIndex != -1) {
            relativePath = requestPath.substring(prefixIndex + prefixLength);
        } else {
            log.error("[Web] Cannot extract relativePath from request, requestPath={}, logId={}", requestPath, logId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        if (relativePath.trim().isEmpty()) {
            log.error("[Web] relativePath is empty, requestPath={}, logId={}", requestPath, logId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // URL decode
        String decodedPath = relativePath;
        try {
            decodedPath = URLDecoder.decode(relativePath, "UTF-8");
        } catch (Exception e) {
            log.warn("[Web] URL decode failed, relativePath={}, using raw path", relativePath, e);
        }
        final String finalRelativePath = decodedPath;

        log.info("[Web] Extracted logId={}, relativePath={}", logId, finalRelativePath);

        try {
            String rangeHeader = request.getHeader(HttpHeaders.RANGE);
            if (StringUtils.isNotBlank(rangeHeader)) {
                log.info("[Web] Client range request, logId={}, range={}", logId, rangeHeader);
            }
            ResponseEntity<Flux<DataBuffer>> downstreamResponse = computerFileDomainService.getStaticFileResponse(
                    cId, targetPrefix, finalRelativePath, logId, rangeHeader
            );
            if (downstreamResponse == null || downstreamResponse.getBody() == null) {
                log.error("[Web] Downstream static file response is empty, logId={}", logId);
                return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to access static file: empty downstream response", logId);
            }

            final Flux<DataBuffer> finalFileFlux = downstreamResponse.getBody();
            HttpHeaders downstreamHeaders = downstreamResponse.getHeaders();

            HttpHeaders headers = new HttpHeaders();
            MediaType downstreamContentType = downstreamHeaders.getContentType();
            if (downstreamContentType != null) {
                headers.setContentType(downstreamContentType);
            } else {
                headers.setContentType(FileTypeUtils.getContentTypeByFileName(finalRelativePath));
            }
            if (downstreamHeaders.getFirst(HttpHeaders.ACCEPT_RANGES) != null) {
                headers.set(HttpHeaders.ACCEPT_RANGES, downstreamHeaders.getFirst(HttpHeaders.ACCEPT_RANGES));
            }
            if (downstreamHeaders.getFirst(HttpHeaders.CONTENT_RANGE) != null) {
                headers.set(HttpHeaders.CONTENT_RANGE, downstreamHeaders.getFirst(HttpHeaders.CONTENT_RANGE));
            }
            if (downstreamHeaders.getContentLength() >= 0) {
                headers.setContentLength(downstreamHeaders.getContentLength());
            }
            if (downstreamHeaders.getFirst(HttpHeaders.ETAG) != null) {
                headers.set(HttpHeaders.ETAG, downstreamHeaders.getFirst(HttpHeaders.ETAG));
            }
            if (downstreamHeaders.getFirst(HttpHeaders.LAST_MODIFIED) != null) {
                headers.set(HttpHeaders.LAST_MODIFIED, downstreamHeaders.getFirst(HttpHeaders.LAST_MODIFIED));
            }

            // If token is obtained from ticket, set Cookie for subsequent requests
            if (authResult.getToken() != null) {
                headers.add("Set-Cookie", "ticket=" + authResult.getToken() + "; Path=/; HttpOnly; SameSite=None; Secure");
                log.info("Set Cookie ticket, logId={}", logId);
            }
            if (authResult.getUserShare() != null) {
                String skCookieKey = getSkCookieKey(conversationUserId, cId);
                UserShareDto userShare = authResult.getUserShare();
                String cookieValue = skCookieKey + userShare.getShareKey() + "; Path=/; SameSite=None; Secure";
                Date expire = userShare.getExpire();
                if (expire != null) {
                    // Calculate the number of seconds from current time to expiration time
                    long maxAge = (expire.getTime() - System.currentTimeMillis()) / 1000;
                    if (maxAge > 0) {
                        cookieValue += "; Max-Age=" + maxAge;
                    } else {
                        // If already expired, set Max-Age=0 to delete cookie immediately
                        cookieValue += "; Max-Age=0";
                    }
                }
                headers.add("Set-Cookie", cookieValue);
            }

            // Create StreamingResponseBody to implement streaming
            StreamingResponseBody streamingResponseBody = outputStream -> {
                try {
                    finalFileFlux
                            .doOnError(WebClientResponseException.class, e -> {
                                log.error("[Web] Failed to get static file, logId={}, status={}, responseBody={}",
                                        logId, e.getStatusCode(), e.getResponseBodyAsString());
                            })
                            .doOnError(SpacePermissionException.class, e -> {
                                log.error("[Web] Insufficient permission for static file, logId={}, {}", logId, e.getMessage());
                            })
                            .doOnError(Throwable.class, e -> {
                                log.error("[Web] Exception while accessing static file, logId={}", logId, e);
                            })
                            .doOnNext(dataBuffer -> {
                                try {
                                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                    dataBuffer.read(bytes);
                                    outputStream.write(bytes);
                                    outputStream.flush();
                                } catch (IOException e) {
                                    log.error("[Web] Failed to write output stream, logId={}", logId, e);
                                    throw new RuntimeException("Failed to write output stream", e);
                                } finally {
                                    DataBufferUtils.release(dataBuffer);
                                }
                            })
                            .doOnComplete(() -> {
                                log.info("[Web] File streaming completed, logId={}, relativePath={}", logId, finalRelativePath);
                            })
                            .blockLast(); // Blocking in StreamingResponseBody callback is normal
                } catch (Exception e) {
                    log.error("[Web] File streaming failed, logId={}", logId, e);
                    // No longer throw exception, because the response has already started writing and cannot change the status code
                    // Errors have been checked and handled before starting to write
                }
            };

            HttpStatus status = HttpStatus.resolve(downstreamResponse.getStatusCode().value());
            if (status == null) {
                status = HttpStatus.OK;
            }
            return ResponseEntity.status(status).headers(headers).body(streamingResponseBody);
        } catch (SpacePermissionException e) {
            log.error("[Web] Insufficient permission for static file, logId={}, {}", logId, e.getMessage());
            return buildErrorResponse(HttpStatus.FORBIDDEN, e.getMessage(), logId, e.getCode());
        } catch (Exception e) {
            log.error("[Web] Failed to access static file, logId={}", logId, e);
            return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to access static file: " + e.getMessage(), logId);
        }
    }

    private ConversationDto getConversation(Long cId) {
        SandboxServerConfig.SandboxServer sandboxServer = null;
        try {
            sandboxServer = sandboxServerConfigService.selectServer(cId);
        } catch (Exception e) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail, e.getMessage());
        }
        if (sandboxServer == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentFileServerNotFound);
        }
        ConversationDto currentConversation = sandboxServer.getCurrentConversation();
        if (currentConversation == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSessionNotFound);
        }
        return currentConversation;
    }

    /**
     * Authentication result, containing userId and token (if obtained from ticket).
     * When redirectUrl is not empty, it means need to redirect to login page, userId is null at this time.
     */
    @Getter
    @AllArgsConstructor
    private static class AuthResult {
        private final Long userId;
        private final String token;
        private final UserShareDto userShare;
        private final String redirectUrl;
    }

    // sk authentication
    private UserShareDto authWithSk(ConversationDto currentConversation, Long cId, HttpServletRequest request) {
        Long conversationUserId = currentConversation.getUserId();
        if (conversationUserId == null) {
            return null;
        }

        // 1. Priority to get from request parameter sk
        String sk = request.getParameter("sk");

        if (StringUtils.isBlank(sk)) {
            // 2. Parse sk from Cookie
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
                            log.info("Successfully obtained user info from URI ticket, userId={}", userDto.getId());
                            currentUserId = userDto.getId();
                            if (!currentUserId.equals(conversationUserId)) {
                                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
                            }
                            return new AuthResult(conversationUserId, token, null, null);
                        } else {
                            log.warn("Failed to obtain user info from URI ticket, token={}", token);
                        }
                    }
                } catch (BizException e) {
                    // Business exception (e.g., insufficient permission) is thrown directly, not caught
                    throw e;
                } catch (Exception e) {
                    log.warn("Failed to obtain user info from URI ticket, ticket={}", ticket, e);
                }
            }
            // Unable to get user info, redirect to login page
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
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
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

    // Build error response (JSON format)
    private ResponseEntity<StreamingResponseBody> buildErrorResponse(HttpStatus status, String message, String logId) {
        return buildErrorResponse(status, message, logId, "0001");
    }

    // Build error response (JSON format)
    private ResponseEntity<StreamingResponseBody> buildErrorResponse(HttpStatus status, String message, String logId, String code) {
        String errorMessage = message != null ? message : "Failed to access static file";

        // Remove sensitive information in IP:port format (e.g., 192.168.1.34:60000)
        // Match IPv4 address:port format, including possible http:// or https:// prefix
        Pattern ipPortPattern = Pattern.compile("(https?://)?\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}:\\d+");
        errorMessage = ipPortPattern.matcher(errorMessage).replaceAll("");
        // Clean up extra spaces
        errorMessage = errorMessage.trim().replaceAll("\\s+", " ");
        // Escape special characters in JSON string
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
                log.error("[Web] Failed to write error response, logId={}", logId, ioException);
            }
        };
        return ResponseEntity.status(status).headers(errorHeaders).body(errorBody);
    }

    private String getSkCookieKey(Long conversationUserId, Long cId) {
        return "static_sk_" + conversationUserId + "_" + cId + "=";
    }
}