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
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class WorkspaceRpcClient {

    @Resource
    private SandboxServerConfigService sandboxServerConfigService;

    private String getBaseUrl(Long cId) {
        SandboxServerConfig.SandboxServer sandboxServer = null;
        try {
            sandboxServer = sandboxServerConfigService.selectServer(cId);
        } catch (BizException e) {
            log.warn("[workspace-client] selectServer failed cId={}", cId, e);
            throw e;
        } catch (Exception e) {
            log.warn("[workspace-client] selectServer failed cId={}", cId, e);
            throw new BizException(e.getMessage());
        }
        if (sandboxServer == null) {
            throw BizException.of(BizExceptionCodeEnum.agentSandboxNotFound);
        }
        String serverUrl = sandboxServer.getServerFileUrl();
        if (serverUrl == null) {
            throw BizException.of(BizExceptionCodeEnum.agentSandboxNotFound);
        }
        return serverUrl + "/api";
    }

    /**
     * 创建工作空间
     */
    public Map<String, Object> createWorkSpace(Long userId, Long cId, MultipartFile file) {
        String url = getBaseUrl(cId) + "/computer/create-workspace";
        log.info("[workspace-client] userId={} cId={} , url={}", userId, cId, url);

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
            log.info("[workspace-client] userId={} cId={} 创建工作空间, 响应结果={}", userId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[workspace-client] userId={} cId={} 创建工作空间失败, status={}, responseBody={}", userId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(userId + "_" + cId, e);
        }
    }

    /**
     * 创建工作空间v2
     */
    public Map<String, Object> createWorkSpaceV2(Long userId, Long cId, MultipartFile file, List<String> skillUrls, String mcpServersConfig, String permissionsConfig, String hooksConfig, String hookScripts) {
        String url = getBaseUrl(cId) + "/computer/create-workspace-v2";
        log.info("[workspace-client] userId={}, cId={}, url={}", userId, cId, url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        // 创建MultiValueMap来存储文件和其他参数
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("userId", String.valueOf(userId));
        body.add("cId", String.valueOf(cId));
        if (file != null) {
            body.add("file", file.getResource());
        }
        if (skillUrls != null && !skillUrls.isEmpty()) {
            body.add("skillUrls", skillUrls);
        }
        if (mcpServersConfig != null && !mcpServersConfig.isBlank()) {
            body.add("mcpServersConfig", mcpServersConfig);
        }
        if (permissionsConfig != null && !permissionsConfig.isBlank()) {
            body.add("permissionsConfig", permissionsConfig);
        }
        if (hooksConfig != null && !hooksConfig.isBlank()) {
            body.add("hooksConfig", hooksConfig);
        }
        if (hookScripts != null && !hookScripts.isBlank()) {
            body.add("hookScripts", hookScripts);
        }

        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[workspace-client] userId={} cId={} 创建工作空间v2, 响应结果={}", userId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[workspace-client] userId={} cId={} 创建工作空间v2失败, status={}, responseBody={}", userId, cId, e.getStatusCode(), e.getResponseBodyAsString());
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
        log.info("[workspace-client] userId={} cId={}, url={}", userId, cId, url);

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
            log.info("[workspace-client] userId={} cId={} 推送技能文件, 响应结果={}", userId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[workspace-client] userId={} cId={} 调用推送技能文件失败, status={}, responseBody={}", userId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(userId + "_" + cId, e);
        }
    }

    /**
     * 推送skill到空间
     * zip 结构与 create-workspace 一致，包含 skills/ 目录
     */
    public Map<String, Object> pushSkillsToWorkspaceV2(Long userId, Long cId, MultipartFile zipFile, List<String> skillUrls) {
        if (zipFile == null && (skillUrls == null || skillUrls.isEmpty())) {
            throw new IllegalArgumentException("No skill files to push");
        }

        String url = getBaseUrl(cId) + "/computer/push-skills-to-workspace-v2";
        log.info("[workspace-client] userId={} cId={}, url={}", userId, cId, url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("userId", String.valueOf(userId));
        body.add("cId", String.valueOf(cId));
        if (zipFile != null) {
            body.add("file", zipFile.getResource());
        }
        if (skillUrls != null && !skillUrls.isEmpty()) {
            body.add("skillUrls", skillUrls);
        }

        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[workspace-client] userId={} cId={} 推送技能文件v2, 响应结果={}", userId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[workspace-client] userId={} cId={} 调用推送技能文件v2失败, status={}, responseBody={}", userId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(userId + "_" + cId, e);
        }
    }

    /**
     * 初始化项目模板
     */
    public Map<String, Object> initProjectTemplate(Long userId, Long cId, MultipartFile file, Boolean enableGit) {
        String url = getBaseUrl(cId) + "/computer/init-project-template";
        log.info("[workspace-client] userId={} cId={}, url={}", userId, cId, url);
        if (file == null) {
            throw new IllegalArgumentException("No template file to init");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("userId", String.valueOf(userId));
        body.add("cId", String.valueOf(cId));
        body.add("file", file.getResource());
        body.add("enableGit", String.valueOf(Boolean.TRUE.equals(enableGit)));
        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[workspace-client] userId={} cId={} 初始化项目模板, 响应结果={}", userId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[workspace-client] userId={} cId={} 初始化项目模板失败, status={}, responseBody={}", userId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(userId + "_" + cId, e);
        }
    }

    /**
     * 安装项目依赖
     */
    public Map<String, Object> installProject(Long userId, Long cId, String programmingLanguage) {
        String url = getBaseUrl(cId) + "/computer/install-project";
        log.info("[workspace-client] userId={} cId={}, url={}, programmingLanguage={}", userId, cId, url, programmingLanguage);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("userId", userId);
        requestBody.put("cId", cId);
        requestBody.put("programmingLanguage", programmingLanguage);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[workspace-client] userId={} cId={} 安装项目依赖, 响应结果={}", userId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[workspace-client] userId={} cId={} 安装项目依赖失败, status={}, responseBody={}", userId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(userId + "_" + cId, e);
        }
    }

    /**
     * 在沙箱工作空间中执行命令
     */
    public Map<String, Object> executeCommand(Long userId, Long cId, String command) {
        String url = getBaseUrl(cId) + "/computer/execute-command";
        log.info("[workspace-client] userId={} cId={}, url={}, command={}", userId, cId, url, command);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("userId", userId);
        requestBody.put("cId", cId);
        requestBody.put("command", command);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[workspace-client] userId={} cId={} 执行命令完成, 响应结果={}", userId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[workspace-client] userId={} cId={} 执行命令失败, status={}, responseBody={}", userId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(userId + "_" + cId, e);
        }
    }

    /**
     * 删除工作空间目录
     */
    public Map<String, Object> deleteWorkspace(Long userId, Long cId) {
        String url = getBaseUrl(cId) + "/computer/delete-workspace";
        log.info("[workspace-client] userId={} cId={}, url={}", userId, cId, url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("userId", userId);
        requestBody.put("cId", cId);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[workspace-client] userId={} cId={} 删除工作空间, 响应结果={}", userId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[workspace-client] userId={} cId={} 删除工作空间失败, status={}, responseBody={}", userId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(userId + "_" + cId, e);
        }
    }

    /**
     * 打包工作空间文件为 zip（二进制流）
     * @param userId 用户ID
     * @param cId 会话ID
     * @param excludeDirs 排除目录列表，null 表示使用 file-server 端默认列表
     * @return zip 字节数组
     */
    public byte[] zipWorkspace(Long userId, Long cId, List<String> excludeDirs) {
        String url = getBaseUrl(cId) + "/computer/zip-workspace";
        log.info("[workspace-client] userId={} cId={}, url={}", userId, cId, url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_OCTET_STREAM));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("userId", userId);
        requestBody.put("cId", cId);
        if (excludeDirs != null) {
            requestBody.put("excludeDirs", excludeDirs);
        }
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<byte[]> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, byte[].class);
            byte[] zipBytes = entity.getBody();
            int size = zipBytes != null ? zipBytes.length : 0;
            log.info("[workspace-client] userId={} cId={} 打包工作空间完成, zipSize={}", userId, cId, size);
            return zipBytes;
        } catch (HttpClientErrorException e) {
            log.warn("[workspace-client] userId={} cId={} 打包工作空间失败, status={}, responseBody={}", userId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BizException("Failed to zip workspace: " + e.getMessage());
        }
    }

    /**
     * 构建 agent 打包产物
     * @param userId 用户ID
     * @param cId 会话ID
     * @param agentId agent ID
     * @param version 版本号
     * @return 响应体，包含 success 和 artifacts 数组
     */
    public Map<String, Object> buildAgentPackage(Long userId, Long cId, Long agentId, String version) {
        String url = getBaseUrl(cId) + "/computer/build-agent-package";
        log.info("[workspace-client] userId={} cId={}, url={}, agentId={}, version={}", userId, cId, url, agentId, version);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("userId", userId);
        requestBody.put("cId", cId);
        requestBody.put("agentId", agentId);
        requestBody.put("version", version);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity,
                    new ParameterizedTypeReference<Map<String, Object>>() {});
            Map<String, Object> responseBody = entity.getBody();
            log.info("[workspace-client] userId={} cId={} 构建 agent 产物完成, 响应结果={}", userId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[workspace-client] userId={} cId={} 构建 agent 产物失败, status={}, responseBody={}", userId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(userId + "_" + cId, e);
        }
    }

    /**
     * 清理沙箱构建产物
     * @param userId 用户ID
     * @param cId 会话ID
     * @return 响应体，包含 success 和 cleaned
     */
    public Map<String, Object> cleanupBuildArtifacts(Long userId, Long cId) {
        String url = getBaseUrl(cId) + "/computer/cleanup-build-artifacts";
        log.info("[workspace-client] userId={} cId={}, url={}", userId, cId, url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("userId", userId);
        requestBody.put("cId", cId);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity,
                    new ParameterizedTypeReference<Map<String, Object>>() {});
            Map<String, Object> responseBody = entity.getBody();
            log.info("[workspace-client] userId={} cId={} 清理构建产物完成, 响应结果={}", userId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[workspace-client] userId={} cId={} 清理构建产物失败, status={}, responseBody={}", userId, cId, e.getStatusCode(), e.getResponseBodyAsString());
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
            if (StringUtils.isNotBlank(responseBody)) {
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
            log.error("[workspace-client] logId={} 解析错误响应体失败", logId, parseException);
        }
        return resultMap;
    }

}