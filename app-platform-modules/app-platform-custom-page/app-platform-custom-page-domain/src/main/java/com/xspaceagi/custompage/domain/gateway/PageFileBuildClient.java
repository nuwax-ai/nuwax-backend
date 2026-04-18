package com.xspaceagi.custompage.domain.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.custompage.domain.dto.PageFileInfo;
import com.xspaceagi.custompage.sdk.dto.ProjectConfigExportDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class PageFileBuildClient {

    @Value("${custom-page.build-server.base-url}")
    private String baseUrl;

    private final WebClient webClient = WebClient.builder().build();

    public Map<String, Object> startDev(Long projectId, String devProxyPath) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/build/start-dev").queryParam("projectId", projectId).queryParam("basePath", devProxyPath).toUriString();
        log.info("[Build-server] project Id={} call dev start API, url={}", projectId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, null, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[Build-server] project Id={} call dev start API, response={}", projectId, body);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] project Id={} call dev start APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> keepAlive(Long projectId, String devProxyPath, Integer devPid, Integer devPort) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/build/keep-alive").queryParam("projectId", projectId).queryParam("basePath", devProxyPath).queryParam("pid", devPid).queryParam("port", devPort).toUriString();
        log.info("[Build-server] project Id={} callkeep-alive API, url={}", projectId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, null, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[Build-server] project Id={} callkeep-alive API, response={}", projectId, body);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] project Id={} callkeep-alive APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> build(Long projectId, String prodProxyPath) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/build/build").queryParam("projectId", projectId).queryParam("basePath", prodProxyPath).toUriString();
        log.info("[Build-server] project Id={} callbuild API, url={}", projectId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, null, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[Build-server] project Id={} callbuild API, response={}", projectId, body);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] project Id={} callbuild APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> stopDev(Long projectId, Integer pid) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/build/stop-dev").queryParam("projectId", projectId).queryParam("pid", pid).toUriString();
        log.info("[Build-server] project Id={} callstop dev server API, url={}", projectId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, null, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[Build-server] project Id={} callstop dev server API, response={}", projectId, body);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] project Id={} callstop dev server APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> restartDev(Long projectId, Integer pid, String devProxyPath) {
        UriComponentsBuilder uriComponentsBuilder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/build/restart-dev").queryParam("projectId", projectId).queryParam("basePath", devProxyPath);

        if (pid != null) {
            uriComponentsBuilder.queryParam("pid", pid);
        }
        String url = uriComponentsBuilder.toUriString();
        log.info("[Build-server] project Id={} callrestart dev server API, url={}", projectId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, null, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[Build-server] project Id={} callrestart dev server API, response={}", projectId, body);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] project Id={} callrestart dev server APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> createProject(Long projectId) {
        String url = baseUrl + "/project/create-project";
        log.info("[Build-server] project Id={} callcreate-project API, url={}", projectId, url);

        // 创建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("projectId", String.valueOf(projectId));

        // 设置请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[Build-server] project Id={} callcreate-project API, response={}", projectId, body);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] project Id={} callcreate-project APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> uploadProject(Long projectId, MultipartFile file, Integer codeVersion, Integer pid, String devProxyPath) {
        String url = baseUrl + "/project/upload-project";
        log.info("[Build-server] project Id={} callupload project API, url={}, code Version={}", projectId, url, codeVersion);

        // 创建请求体，包含文件、projectId和版本号
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        // 创建MultiValueMap来存储文件、projectId和版本号
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file.getResource());
        body.add("projectId", String.valueOf(projectId));
        body.add("codeVersion", String.valueOf(codeVersion));
        body.add("pid", pid);
        if (devProxyPath != null) {
            body.add("basePath", devProxyPath);
        }

        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[Build-server] project Id={} callupload project API, response={}", projectId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] project Id={} callupload project APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> getProjectContent(Long projectId, String command, String proxyPath) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/project/get-project-content")
                .queryParam("projectId", projectId)
                .queryParam("command", command)
                .queryParam("proxyPath", proxyPath)
                .toUriString();
        log.info("[Build-server] project Id={} callqueryprojectcontent API, url={}", projectId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, null, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[Build-server] project Id={} callqueryprojectcontent API, response sent", projectId);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] project Id={} callqueryprojectcontent APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> getProjectContentByVersion(Long projectId, Integer codeVersion, String command, String proxyPath) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/project/get-project-content-by-version")
                .queryParam("projectId", projectId)
                .queryParam("codeVersion", codeVersion)
                .queryParam("command", command)
                .queryParam("proxyPath", proxyPath)
                .toUriString();
        log.info("[Build-server] project Id={} callqueryprojecthistorical version content API, url={}", projectId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, null, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[Build-server] project Id={} callqueryprojecthistorical version content API, response sent", projectId);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] project Id={} callqueryprojecthistorical version content APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    /**
     * codeVersion 是当前版本，上传后版本会+1
     */
    public Map<String, Object> specifiedFilesUpdate(Long projectId, List<PageFileInfo> files, Integer codeVersion, String devProxyPath, Integer devPid) {
        String url = baseUrl + "/project/specified-files-update";
        log.info("[Build-server] project Id={} specifiedfileupdate, url={}", projectId, url);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("projectId", String.valueOf(projectId));
        requestBody.put("files", files);
        requestBody.put("codeVersion", codeVersion);
        requestBody.put("basePath", devProxyPath);
        requestBody.put("pid", devPid);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[Build-server] project Id={} specifiedfileupdate, response={}", projectId, body);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] project Id={} callspecifiedfileupdate APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    /**
     * codeVersion 是当前版本，上传后版本会+1
     */
    public Map<String, Object> allFilesUpdate(Long projectId, List<PageFileInfo> files, Integer codeVersion, String devProxyPath, Integer devPid) {
        String url = baseUrl + "/project/all-files-update";
        log.info("[Build-server] project Id={} fullfileupdate, url={}", projectId, url);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("projectId", String.valueOf(projectId));
        requestBody.put("files", files);
        requestBody.put("codeVersion", codeVersion);
        requestBody.put("basePath", devProxyPath);
        requestBody.put("pid", devPid);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[Build-server] project Id={} fullfileupdate, response={}", projectId, body);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] project Id={} callfullfileupdate APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    /**
     * codeVersion 是当前版本，上传后版本会+1
     */
    public Map<String, Object> uploadSingleFile(Long projectId, MultipartFile file, String filePath, Integer codeVersion) {
        String url = baseUrl + "/project/upload-single-file";
        log.info("[Build-server] project Id={} upload single file, url={}, file Path={}, code Version={}", projectId, url, filePath, codeVersion);

        // 创建请求体，包含文件、projectId、filePath和codeVersion
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        // 创建MultiValueMap来存储文件和其他参数
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file.getResource());
        body.add("projectId", String.valueOf(projectId));
        body.add("filePath", filePath);
        body.add("codeVersion", String.valueOf(codeVersion));

        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[Build-server] project Id={} upload single file, response={}", projectId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] project Id={} callupload single file APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> uploadAttachmentFile(Long projectId, MultipartFile file, String uploadFileName) {
        String url = baseUrl + "/project/upload-attachment-file";
        log.info("[Build-server] project Id={} upload attachment, url={}, upload File Name={}", projectId, url, uploadFileName);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file.getResource());
        body.add("projectId", String.valueOf(projectId));
        body.add("fileName", uploadFileName);

        HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> responseBody = entity.getBody();
            log.info("[Build-server] project Id={} upload attachment, response={}", projectId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] project Id={} callupload attachment APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> backupCurrentVersion(Long projectId, Integer codeVersion) {
        String url = baseUrl + "/project/backup-current-version";
        log.info("[Build-server] project Id={} backup current version, url={}, code Version={}", projectId, url, codeVersion);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("projectId", String.valueOf(projectId));
        requestBody.put("codeVersion", codeVersion);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[Build-server] project Id={} backup current version, response={}", projectId, body);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] project Id={} callbackup current version APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    /**
     * codeVersion 是当前版本，回滚后版本会+1
     */
    public Map<String, Object> rollbackVersion(Long projectId, Integer rollbackTo, Integer codeVersion, String devProxyPath, Integer devPid) {
        String url = baseUrl + "/project/rollback-version";
        log.info("[Build-server] project Id={} rollback version, url={}, rollback To={}, code Version={}", projectId, url, rollbackTo, codeVersion);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("projectId", String.valueOf(projectId));
        requestBody.put("rollbackTo", rollbackTo);
        requestBody.put("codeVersion", codeVersion);
        requestBody.put("basePath", devProxyPath);
        requestBody.put("pid", devPid);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[Build-server] project Id={} rollback version, response={}", projectId, body);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] project Id={} callrollback version APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public InputStream exportProject(Long projectId, Integer codeVersion, String exportType, ProjectConfigExportDto configExportDto) {
        String url = baseUrl + "/project/export-project";
        log.info("[Build-server] project Id={} exportproject, url={}, code Version={}, export Type={}", projectId, url, codeVersion, exportType);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("projectId", String.valueOf(projectId));
        requestBody.put("codeVersion", codeVersion);
        requestBody.put("exportType", exportType);
        requestBody.put("config", configExportDto);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<byte[]> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, byte[].class);

        byte[] body = entity.getBody();
        log.info("[Build-server] project Id={} exportproject, response size={} bytes", projectId, body != null ? body.length : 0);
        return body != null ? new ByteArrayInputStream(body) : null;
    }

    public Map<String, Object> deleteProject(Long projectId, Integer pid) {
        UriComponentsBuilder uriComponentsBuilder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/project/delete-project").queryParam("projectId", projectId).queryParam("pid", pid);

        String url = uriComponentsBuilder.toUriString();
        log.info("[Build-server] project Id={} calldelete project API, url={}", projectId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, null, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[Build-server] project Id={} calldelete project API, response={}", projectId, body);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] project Id={} calldelete project APIfailed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> getDevLog(Long projectId, Integer startIndex, String logType) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/build/get-dev-log")
                .queryParam("projectId", projectId)
                .queryParam("startIndex", startIndex)
                .queryParam("logType", logType)
                .toUriString();
        log.debug("[Build-server] project Id={} callquery logs API, url={}", projectId, url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, null, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.debug("[Build-server] project Id={} callquery logs API, response sent", projectId);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] project Id={} query logs API failed, status={}, response Body={}", projectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(projectId, e);
        }
    }

    public Map<String, Object> copyProject(Long sourceProjectId, Long targetProjectId) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/project/copy-project")
                //.queryParam("sourceProjectId", sourceProjectId)
                //.queryParam("targetProjectId", targetProjectId)
                .toUriString();
        log.info("[Build-server] source Project Id={},target Project Id={}, callcopyproject API, url={}", sourceProjectId, targetProjectId, url);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("sourceProjectId", String.valueOf(sourceProjectId));
        requestBody.put("targetProjectId", targetProjectId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[Build-server] source Project Id={},target Project Id={}, callcopyproject API, response={}", sourceProjectId, targetProjectId, body);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] project Id={} query logs API failed, status={}, response Body={}", sourceProjectId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(sourceProjectId, e);
        }
    }

    public Map<String, Object> getLogCacheStats() {
        String url = baseUrl + "/build/get-log-cache-stats";
        log.info("[Build-server] callgetlogcachestats API, url={}", url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, null, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[Build-server] callgetlogcachestats API, response={}", body);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] callgetlogcachestats APIfailed, status={}, response Body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(null, e);
        }
    }

    /**
     * 获取静态文件（流式返回）
     */
    public Flux<DataBuffer> getStaticFile(String targetPrefix, String relativePath, String logId) {
        // 使用 UriComponentsBuilder 来正确处理路径编码
        String[] prefixSegments = Arrays.stream(targetPrefix.split("/")).filter(segment -> !segment.isEmpty()).toArray(String[]::new);
        String[] relativeSegments = Arrays.stream(relativePath.split("/")).filter(segment -> !segment.isEmpty()).toArray(String[]::new);

        String url = UriComponentsBuilder.fromHttpUrl(baseUrl).pathSegment(prefixSegments).pathSegment(relativeSegments).toUriString();
        log.info("[Build-server] log Id={} callgetstaticfile API, url={}, target Prefix={}, relative Path={}", logId, url, targetPrefix, relativePath);

        return webClient.get()
                .uri(url)
                .accept(MediaType.ALL) // 接受所有媒体类型，因为静态文件可能是图片、文本等
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .doOnError(WebClientResponseException.class, e -> {
                    log.warn("[Build-server] log Id={} callgetstaticfile APIfailed, status={}, response Body={}", logId, e.getStatusCode(), e.getResponseBodyAsString());
                }).doOnError(Throwable.class, e -> {
                    log.error("[Build-server] log Id={} callgetstaticfile APIexception", logId, e);
                }).doOnDiscard(DataBuffer.class, DataBufferUtils::release).doOnComplete(() -> {
                    log.info("[Build-server] log Id={} callgetstaticfile API, streaming completed", logId);
                });
    }

    public Map<String, Object> clearAllLogCache() {
        String url = baseUrl + "/build/clear-all-log-cache";
        log.info("[Build-server] callclearlogcache API, url={}", url);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(url, HttpMethod.GET, null, new ParameterizedTypeReference<Map<String, Object>>() {
            });
            Map<String, Object> body = entity.getBody();
            log.info("[Build-server] callclearlogcache API, response={}", body);
            return body;
        } catch (HttpClientErrorException e) {
            log.warn("[Build-server] callclearlogcache APIfailed, status={}, response Body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(null, e);
        }
    }

    // 捕获4xx错误，尝试解析响应体
    private Map<String, Object> parseClientErr(Long projectId, HttpClientErrorException e) {
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
            log.error("[Build-server] project Id={} parse error response body failed", projectId, parseException);
        }
        return resultMap;
    }

}