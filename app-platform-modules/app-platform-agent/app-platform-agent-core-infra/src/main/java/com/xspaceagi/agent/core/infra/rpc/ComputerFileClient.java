package com.xspaceagi.agent.core.infra.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.adapter.dto.ComputerFileInfo;
import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.system.spec.exception.BizException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ComputerFileClient {

    @Resource
    private SandboxServerConfigService sandboxServerConfigService;

    private final WebClient webClient = WebClient.builder().build();

    private String getVncFileServerUrl(Long cId) {
        SandboxServerConfig.SandboxServer sandboxServer = null;
        try {
            sandboxServer = sandboxServerConfigService.selectServer(cId);
        } catch (Exception e) {
            throw new BizException(e.getMessage());
        }
        if (sandboxServer == null) {
            return null;
        }
        return sandboxServer.getServerFileUrl();
    }

    private String getBaseUrl(Long cId) {
        String serverUrl = getVncFileServerUrl(cId);
        if (serverUrl == null) {
            throw new BizException("未找到文件服务器");
        }
        return serverUrl + "/api";
    }

    /**
     * 获取文件列表
     */
    public Map<String, Object> getFileList(Long userId, Long cId, String proxyPath) {
        String url = UriComponentsBuilder.fromHttpUrl(getBaseUrl(cId) + "/computer/get-file-list")
                .queryParam("userId", userId)
                .queryParam("cId", cId)
                .queryParam("proxyPath", proxyPath)
                .toUriString();
        log.info("[computer-client] userId={} cId={} 调用获取文件列表接口, url={}", userId, cId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, null, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[computer-client] userId={} cId={} 调用获取文件列表接口, 已响应", userId, cId);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[computer-client] userId={} cId={} 调用获取文件列表接口失败, status={}, responseBody={}", userId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(userId + "_" + cId, e);
        }
    }

    /**
     * 获取静态文件（流式返回）
     */
    public Flux<DataBuffer> getStaticFile(Long cId, String targetPrefix, String relativePath, String logId) {
        // 使用 UriComponentsBuilder 来正确处理路径编码
        String[] prefixSegments = Arrays.stream(targetPrefix.split("/")).filter(segment -> !segment.isEmpty()).toArray(String[]::new);
        String[] relativeSegments = Arrays.stream(relativePath.split("/")).filter(segment -> !segment.isEmpty()).toArray(String[]::new);

        String url = UriComponentsBuilder.fromHttpUrl(getBaseUrl(cId)).pathSegment(prefixSegments).pathSegment(relativeSegments).toUriString();
        log.info("[computer-client] logId={} 调用获取静态文件接口, url={}, targetPrefix={}, relativePath={}", logId, url, targetPrefix, relativePath);

        return webClient.get()
                .uri(url)
                .accept(MediaType.ALL) // 接受所有媒体类型，因为静态文件可能是图片、文本等
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .doOnError(WebClientResponseException.class, e -> {
            log.warn("[computer-client] logId={} 调用获取静态文件接口失败, status={}, responseBody={}", logId, e.getStatusCode(), e.getResponseBodyAsString());
        }).doOnError(Throwable.class, e -> {
            log.error("[computer-client] logId={} 调用获取静态文件接口异常", logId, e);
        }).doOnDiscard(DataBuffer.class, DataBufferUtils::release).doOnComplete(() -> {
            log.info("[computer-client] logId={} 调用获取静态文件接口, 流式传输完成", logId);
        });
    }

    /**
     * 获取静态文件（阻塞式，返回完整byte[]）
     */
    public byte[] getStaticFileAsBytes(Long cId, String staticPrefix, String relativePath, String logId) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            getStaticFile(cId, staticPrefix, relativePath, logId).doOnNext(dataBuffer -> {
                try {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    outputStream.write(bytes);
                } catch (IOException e) {
                    log.error("[computer-client] logId={} 读取DataBuffer失败", logId, e);
                    throw new RuntimeException("读取DataBuffer失败", e);
                } finally {
                    DataBufferUtils.release(dataBuffer);
                }
            }).blockLast();
            byte[] body = outputStream.toByteArray();
            log.info("[computer-client] logId={} 调用获取静态文件接口, 响应大小={} bytes", logId, body != null ? body.length : 0);
            return body;
        } catch (Exception e) {
            log.error("[computer-client] logId={} 获取静态文件失败", logId, e);
            throw new RuntimeException("获取静态文件失败", e);
        }
    }

    /**
     * 更新文件列表
     */
    public Map<String, Object> filesUpdate(Long userId, Long cId, List<ComputerFileInfo> files) {
        String url = getBaseUrl(cId) + "/computer/files-update";
        log.info("[computer-client] userId={} cId={} 更新文件列表, url={}, filesCount={}", userId, cId, url, files != null ? files.size() : 0);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("userId", userId);
        requestBody.put("cId", cId);
        requestBody.put("files", files);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[computer-client] userId={} cId={} 更新文件列表, 响应结果={}", userId, cId, body);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[computer-client] userId={} cId={} 调用更新文件列表接口失败, status={}, responseBody={}", userId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(userId + "_" + cId, e);
        }
    }

    /**
     * 上传文件
     */
    public Map<String, Object> uploadFile(Long userId, Long cId, String filePath, MultipartFile file) {
        String url = getBaseUrl(cId) + "/computer/upload-file";
        log.info("[computer-client] userId={} cId={} 上传用户文件, url={}, filePath={}", userId, cId, url, filePath);

        // 创建请求体，包含文件、userId、cId和filePath
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        // 创建MultiValueMap来存储文件和其他参数
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file.getResource());
        body.add("userId", String.valueOf(userId));
        body.add("cId", String.valueOf(cId));
        body.add("filePath", filePath);

        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[computer-client] userId={} cId={} 上传文件, 响应结果={}", userId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[computer-client] userId={} cId={} 调用上传文件接口失败, status={}, responseBody={}", userId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(userId + "_" + cId, e);
        }
    }

    /**
     * 批量上传文件
     */
    public Map<String, Object> uploadFiles(Long userId, Long cId, List<String> filePaths, List<MultipartFile> files) {
        String url = getBaseUrl(cId) + "/computer/upload-files";
        log.info("[computer-client] userId={} cId={} 批量上传用户文件, url={}, fileCount={}, filePathsCount={}", userId, cId, url,
                files != null ? files.size() : 0, filePaths != null ? filePaths.size() : 0);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("userId", String.valueOf(userId));
        body.add("cId", String.valueOf(cId));

        // 确保 filePaths 始终作为数组传递，即使只有一个值
        // 使用循环 add 方法确保每个值都作为数组元素传递
        // 这样 RestTemplate 在发送 multipart/form-data 时，会将同一个 key 的多个值作为数组发送
        for (String filePath : filePaths) {
            body.add("filePaths", filePath);
        }

        for (MultipartFile file : files) {
            body.add("files", file.getResource());
        }

        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity,
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[computer-client] userId={} cId={} 批量上传文件, 响应结果={}", userId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[computer-client] userId={} cId={} 调用批量上传文件接口失败, status={}, responseBody={}", userId, cId,
                    e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(userId + "_" + cId, e);
        }
    }

    /**
     * 下载全部文件（zip 流式返回）
     */
    public Flux<DataBuffer> downloadAllFiles(Long userId, Long cId, String logId) {
        String url = UriComponentsBuilder
                .fromHttpUrl(getBaseUrl(cId) + "/computer/download-all-files")
                .queryParam("userId", userId)
                .queryParam("cId", cId)
                .toUriString();
        log.info("[computer-client] logId={} 调用下载全部文件接口, url={}", logId, url);

        return webClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .doOnError(WebClientResponseException.class, e -> {
                    log.warn("[computer-client] logId={} 调用下载全部文件接口失败, status={}, responseBody={}", logId,
                            e.getStatusCode(), e.getResponseBodyAsString());
                })
                .doOnError(Throwable.class, e -> {
                    log.error("[computer-client] logId={} 调用下载全部文件接口异常", logId, e);
                })
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                .doOnComplete(() -> {
                    log.info("[computer-client] logId={} 调用下载全部文件接口, 流式传输完成", logId);
                });
    }

    // 捕获4xx错误，尝试解析响应体
    private Map<String, Object> parseClientErr(String logId, HttpClientErrorException e) {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", false);
        resultMap.put("message", e.getMessage());
        try {
            String responseBody = e.getResponseBodyAsString();
            if (responseBody != null && !responseBody.isEmpty()) {
                ObjectMapper objectMapper = new ObjectMapper();
                @SuppressWarnings("unchecked") Map<String, Object> errorResponse = objectMapper.readValue(responseBody, Map.class);
                if (errorResponse.containsKey("code")) {
                    resultMap.put("code", errorResponse.get("code"));
                }
                if (errorResponse.containsKey("message")) {
                    resultMap.put("message", errorResponse.get("message"));
                } else if (errorResponse.containsKey("error")) {
                    Object errorObj = errorResponse.get("error");
                    if (errorObj instanceof Map) {
                        @SuppressWarnings("unchecked") Map<String, Object> errorMap = (Map<String, Object>) errorObj;
                        if (errorMap.containsKey("message")) {
                            resultMap.put("message", errorMap.get("message"));
                        }
                    }
                }
            }
        } catch (Exception parseException) {
            log.error("[computer-client] logId={} 解析错误响应体失败", logId, parseException);
        }
        return resultMap;
    }

}