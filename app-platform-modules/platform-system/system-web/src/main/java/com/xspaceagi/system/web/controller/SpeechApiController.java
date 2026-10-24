package com.xspaceagi.system.web.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.xspaceagi.eco.market.sdk.reponse.ClientSecretResponse;
import com.xspaceagi.system.infra.rpc.ClientSecretRpcService;
import com.xspaceagi.system.sdk.service.UserAccessKeyApiService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.web.dto.SttResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

@Tag(name = "语音转换相关接口")
@Slf4j
@RestController
public class SpeechApiController {

    @Value("${stt.api.base-url:}")
    private String baseUrl;

    @Resource
    private UserAccessKeyApiService userAccessKeyApiService;

    @Resource
    private ClientSecretRpcService clientSecretRpcService;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).version(HttpClient.Version.HTTP_1_1).build();

    @Operation(summary = "语音转文本", description = "上传语音文件，转换为文本")
    @PostMapping("/api/audio/stt")
    public ReqResult<SttResult> stt(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        if (!RequestContext.get().isLogin()) {
            ChatKeyCheck.check(request, userAccessKeyApiService);
        }
        if (file.isEmpty()) {
            return ReqResult.error("Please select a file to upload");
        }

        try {
            ClientSecretResponse clientSecretResponse = clientSecretRpcService.queryClientSecret(RequestContext.get().getTenantId());
            if (clientSecretResponse == null) {
                return ReqResult.error("Client secret not found");
            }
            String content = uploadFile(baseUrl, file.getBytes(), clientSecretResponse.getClientSecret());
            ReqResult result = JSON.parseObject(content, new TypeReference<ReqResult<SttResult>>() {
            });
            return result;
        } catch (Exception e) {
            log.error("File upload failed", e);
            return ReqResult.error("File upload failed");
        }
    }

    public String uploadFile(String baseUrl, byte[] fileBytes, String clientSecret) throws Exception {
        String boundary = UUID.randomUUID().toString();
        String header = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"audio\"; filename=\"" + "audio.wav" + "\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";

        byte[] headerBytes = header.getBytes();
        byte[] footerBytes = footer.getBytes();

        byte[] requestBody = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, requestBody, 0, headerBytes.length);
        System.arraycopy(fileBytes, 0, requestBody, headerBytes.length, fileBytes.length);
        System.arraycopy(footerBytes, 0, requestBody, headerBytes.length + fileBytes.length, footerBytes.length);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/transcribe"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + clientSecret)
                .header("Request-Id", UUID.randomUUID().toString())
                .timeout(java.time.Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }
}
