package com.xspaceagi.agent.core.infra.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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

    private SandboxServerConfig.SandboxServer requireServer(Long cId) {
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
        if (sandboxServer.getServerFileUrl() == null) {
            throw BizException.of(BizExceptionCodeEnum.agentSandboxNotFound);
        }
        return sandboxServer;
    }

    private String fileUrl(SandboxServerConfig.SandboxServer sandboxServer, String path) {
        return sandboxServer.getServerFileUrl() + "/api" + path;
    }

    /**
     * 创建工作空间
     */
    public Map<String, Object> createWorkSpace(Long userId, Long cId, MultipartFile file) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        String url = fileUrl(sandboxServer, "/computer/create-workspace");
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        log.info("[workspace-client] userId={} cId={} serviceType={} appId={}, url={}", userId, cId,
                serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);

        // 创建MultiValueMap来存储文件和其他参数
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("userId", String.valueOf(userId));
        body.add("cId", String.valueOf(cId));
        WorkspaceServiceSupport.applyServiceParams(body, serviceContext);
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
     *
     * @param skillUrls        技能 zip 的 url 数组（旧 FS 依赖此字段；必须是 url 列表）
     * @param skillUrlMap      技能名→url（新 FS 下载前按名跳过；旧 FS 可忽略）；技能名=skill.md 的 name
     * @param skillNames       配置技能名全集（新 FS prune；旧 FS 可忽略）
     * @param updateSkillNames 强制更新的技能名（新 FS 按需安装；旧 FS 可忽略）
     */
    public Map<String, Object> createWorkSpaceV2(Long userId, Long cId, Long agentId, MultipartFile file,
                                                 List<String> skillUrls, Map<String, String> skillUrlMap,
                                                 List<String> skillNames, List<String> updateSkillNames,
                                                 String mcpServersConfig, String permissionsConfig,
                                                 String hooksConfig, String hookScripts) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        String url = fileUrl(sandboxServer, "/computer/create-workspace-v2");
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        log.info("[workspace-client] userId={}, agentId={}, cId={} serviceType={} appId={}, url={}", userId, agentId, cId,
                serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);

        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("userId", String.valueOf(userId));
        body.add("cId", String.valueOf(cId));
        WorkspaceServiceSupport.applyServiceParams(body, serviceContext);
        if (agentId != null) {
            body.add("agentId", String.valueOf(agentId));
        }
        if (file != null) {
            body.add("file", file.getResource());
        }
        ObjectMapper objectMapper = new ObjectMapper();
        if (skillUrls != null && !skillUrls.isEmpty()) {
            try {
                body.add("skillUrls", objectMapper.writeValueAsString(skillUrls));
            } catch (Exception e) {
                for (String skillUrl : skillUrls) {
                    body.add("skillUrls", skillUrl);
                }
            }
        }
        if (skillUrlMap != null && !skillUrlMap.isEmpty()) {
            try {
                body.add("skillUrlMap", objectMapper.writeValueAsString(skillUrlMap));
            } catch (Exception e) {
                log.warn("[workspace-client] serialize skillUrlMap failed", e);
            }
        }
        if (skillNames != null && !skillNames.isEmpty()) {
            try {
                body.add("skillNames", objectMapper.writeValueAsString(skillNames));
            } catch (Exception e) {
                body.add("skillNames", String.join(",", skillNames));
            }
        }
        if (updateSkillNames != null) {
            try {
                body.add("updateSkillNames", objectMapper.writeValueAsString(updateSkillNames));
            } catch (Exception e) {
                body.add("updateSkillNames", String.join(",", updateSkillNames));
            }
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
            log.info("[workspace-client] userId={} agentId={} cId={} 创建工作空间v2, 响应结果={}", userId, agentId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[workspace-client] userId={} agentId={} cId={} 创建工作空间v2失败, status={}, responseBody={}", userId, agentId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(userId + "_" + cId, e);
        }
    }

    /**
     * 推送skill到空间
     * zip 结构与 create-workspace 一致，包含 skills/ 目录
     * 老版本接口：通过压缩包传文件
     */
    public Map<String, Object> pushSkillsToWorkspace(Long userId, Long cId, MultipartFile zipFile) {
        if (zipFile == null) {
            throw new IllegalArgumentException("No skill files to push");
        }

        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        String url = fileUrl(sandboxServer, "/computer/push-skills-to-workspace");
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        log.info("[workspace-client] userId={} cId={} serviceType={} appId={}, url={}", userId, cId,
                serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);

        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("userId", String.valueOf(userId));
        body.add("cId", String.valueOf(cId));
        WorkspaceServiceSupport.applyServiceParams(body, serviceContext);
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
     * 新版本接口：通过技能url传技能，老技能（无url）还是通过压缩包传输
     */
    public Map<String, Object> pushSkillsToWorkspaceV2(Long userId, Long cId, Long agentId, MultipartFile zipFile, List<String> skillUrls) {
        if (zipFile == null && (skillUrls == null || skillUrls.isEmpty())) {
            throw new IllegalArgumentException("No skill files to push");
        }

        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        String url = fileUrl(sandboxServer, "/computer/push-skills-to-workspace-v2");
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        log.info("[workspace-client] userId={} agentId={} cId={} serviceType={} appId={}, url={}", userId, agentId, cId,
                serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);

        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("userId", String.valueOf(userId));
        body.add("cId", String.valueOf(cId));
        WorkspaceServiceSupport.applyServiceParams(body, serviceContext);
        if (agentId != null) {
            body.add("agentId", String.valueOf(agentId));
        }
        if (zipFile != null) {
            body.add("file", zipFile.getResource());
        }
        if (skillUrls != null && !skillUrls.isEmpty()) {
            try {
                body.add("skillUrls", new ObjectMapper().writeValueAsString(skillUrls));
            } catch (Exception e) {
                for (String skillUrl : skillUrls) {
                    body.add("skillUrls", skillUrl);
                }
            }
        }

        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[workspace-client] userId={} agentId={} cId={} 推送技能文件v2, 响应结果={}", userId, agentId, cId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[workspace-client] userId={} agentId={} cId={} 调用推送技能文件v2失败, status={}, responseBody={}", userId, agentId, cId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(userId + "_" + cId, e);
        }
    }

    /**
     * 初始化项目模板
     */
    public Map<String, Object> initProjectTemplate(Long userId, Long cId, MultipartFile file, Boolean enableGit) {
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        String url = fileUrl(sandboxServer, "/computer/init-project-template");
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        log.info("[workspace-client] userId={} cId={} serviceType={} appId={}, url={}", userId, cId,
                serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), url);
        if (file == null) {
            throw new IllegalArgumentException("No template file to init");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);

        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("userId", String.valueOf(userId));
        body.add("cId", String.valueOf(cId));
        WorkspaceServiceSupport.applyServiceParams(body, serviceContext);
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
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        String url = fileUrl(sandboxServer, "/computer/install-project");
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        log.info("[workspace-client] userId={} cId={} serviceType={} appId={}, url={}, programmingLanguage={}", userId, cId,
                serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), url, programmingLanguage);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("userId", userId);
        requestBody.put("cId", cId);
        WorkspaceServiceSupport.applyServiceParams(requestBody, serviceContext);
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
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        String url = fileUrl(sandboxServer, "/computer/execute-command");
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        log.info("[workspace-client] userId={} cId={} serviceType={} appId={}, url={}, command={}", userId, cId,
                serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), url, command);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("userId", userId);
        requestBody.put("cId", cId);
        WorkspaceServiceSupport.applyServiceParams(requestBody, serviceContext);
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
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        String url = fileUrl(sandboxServer, "/computer/delete-workspace");
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        log.info("[workspace-client] userId={} cId={} serviceType={} appId={}, url={}", userId, cId,
                serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("userId", userId);
        requestBody.put("cId", cId);
        WorkspaceServiceSupport.applyServiceParams(requestBody, serviceContext);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        // 带超时：该调用可能发生在项目删除事务内，file-server 挂起时不能无限占用数据库连接
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);
        requestFactory.setReadTimeout(60_000);
        RestTemplate restTemplate = new RestTemplate(requestFactory);
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
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        String url = fileUrl(sandboxServer, "/computer/zip-workspace");
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        log.info("[workspace-client] userId={} cId={} serviceType={} appId={}, url={}", userId, cId,
                serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_OCTET_STREAM));
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("userId", userId);
        requestBody.put("cId", cId);
        WorkspaceServiceSupport.applyServiceParams(requestBody, serviceContext);
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
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        String url = fileUrl(sandboxServer, "/computer/build-agent-package");
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        log.info("[workspace-client] userId={} cId={} serviceType={} appId={}, url={}, agentId={}, version={}", userId, cId,
                serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), url, agentId, version);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("userId", userId);
        requestBody.put("cId", cId);
        WorkspaceServiceSupport.applyServiceParams(requestBody, serviceContext);
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
        SandboxServerConfig.SandboxServer sandboxServer = requireServer(cId);
        String url = fileUrl(sandboxServer, "/computer/cleanup-build-artifacts");
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer);
        log.info("[workspace-client] userId={} cId={} serviceType={} appId={}, url={}", userId, cId,
                serviceContext.getServiceType().getWorkspaceType(), serviceContext.getAppId(), url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        WorkspaceServiceSupport.applyContainerRouteHeaders(headers, serviceContext, sandboxServer);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("userId", userId);
        requestBody.put("cId", cId);
        WorkspaceServiceSupport.applyServiceParams(requestBody, serviceContext);
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
