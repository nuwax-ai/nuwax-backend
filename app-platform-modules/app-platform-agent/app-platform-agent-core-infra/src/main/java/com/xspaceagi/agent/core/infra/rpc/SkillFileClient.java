package com.xspaceagi.agent.core.infra.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class SkillFileClient {

    @Resource
    private SandboxServerConfigService sandboxServerConfigService;

    private String getBaseUrl(Long cId) {
        SandboxServerConfig.SandboxServer sandboxServer = null;
        try {
            sandboxServer = sandboxServerConfigService.selectServer(cId);
        } catch (Exception e) {
            log.warn("[SkillFileClient] selectServer failed cId={}", cId, e);
            throw BizException.of(BizExceptionCodeEnum.agentDependencyServiceError);
        }
        if (sandboxServer == null) {
            return null;
        }
        String serverUrl = sandboxServer.getServerFileUrl();
        if (serverUrl == null) {
            throw BizException.of(BizExceptionCodeEnum.agentFileServerNotFound);
        }
        return serverUrl + "/api";
    }

    /**
     * 创建工作空间
     */
    public Map<String, Object> createWorkSpace(Long userId, Long cId, MultipartFile file) {
        String url = getBaseUrl(cId) + "/computer/create-workspace";
        log.info("[Skill-client] userId={} cId={} , url={}", userId, cId, url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        // 创建MultiValueMap来存储文件和其他参数
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("userId", String.valueOf(userId));
        body.add("cId", String.valueOf(cId));
        if (file != null) {
            body.add("file", file.getResource());
        }

        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[Skill-client] userId={} cId={} 创建工作空间, 响应结果={}", userId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[Skill-client] userId={} cId={} 创建工作空间失败, status={}, responseBody={}", userId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(userId + "_" + cId, e);
        }
    }

    /**
     * 推送skill到空间
     * zip 结构与 create-workspace 一致，包含 skills/ 目录
     */
    public Map<String, Object> pushSkillsToWorkspace(Long userId, Long cId, MultipartFile zipFile) {
        if (zipFile == null) {
            throw new IllegalArgumentException("No skill files to push");
        }

        String url = getBaseUrl(cId) + "/computer/push-skills-to-workspace";
        log.info("[Skill-client] userId={} cId={}, url={}", userId, cId, url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("userId", String.valueOf(userId));
        body.add("cId", String.valueOf(cId));
        body.add("file", zipFile.getResource());

        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[Skill-client] userId={} cId={} 推送技能文件, 响应结果={}", userId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[Skill-client] userId={} cId={} 调用推送技能文件失败, status={}, responseBody={}", userId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(userId + "_" + cId, e);
        }
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
            log.error("[Skill-client] logId={} 解析错误响应体失败", logId, parseException);
        }
        return resultMap;
    }

}