package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.infra.rpc.SandboxServerConfigService;
import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;

@Tag(name = "ACP 权限审批回调接口")
@RestController
@RequestMapping("/api/agent-interventions")
@Slf4j
public class InterventionController {

    @Resource
    private SandboxServerConfigService sandboxServerConfigService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private ConversationApplicationService conversationApplicationService;

    private final RestTemplate restTemplate = new RestTemplate();

    @PostMapping("/{interventionId}/respond")
    public ResponseEntity<Map<String, Object>> respond(
            @PathVariable String interventionId,
            @RequestBody Map<String, Object> requestBody) {

        log.info("[ACP PERMISSION] Intervention respond received, interventionId={}", interventionId);

        try {
            Long userId = RequestContext.get().getUserId();
            TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(RequestContext.get().getTenantId());
            SandboxServerConfig.SandboxServer sandboxServer = null;

            Object cidObj = requestBody.get("conversation_id");
            if (cidObj == null) {
                cidObj = requestBody.get("conversationId");
            }
            if (cidObj != null) {
                try {
                    Long cid = Long.parseLong(cidObj.toString());
                    ConversationDto conversation = TenantFunctions.callWithIgnoreCheck(() ->
                            conversationApplicationService.getConversationByCid(cid));
                    if (conversation != null && conversation.getSandboxServerId() != null) {
                        log.info("[ACP PERMISSION] Found conversation cid={}, sandboxServerId={}", cid, conversation.getSandboxServerId());
                        sandboxServer = sandboxServerConfigService.getServerBypassOnlineCheck(
                                tenantConfig.getTenantId(), conversation.getSandboxServerId());
                    }
                } catch (Exception e) {
                    log.warn("[ACP PERMISSION] Failed to get sandbox server from conversation", e);
                }
            }
            if (sandboxServer == null) {
                sandboxServer = sandboxServerConfigService.selectServer(tenantConfig, userId, null);
            }
            if (sandboxServer == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("ok", false, "error", Map.of("code", "not_found", "message", "No sandbox server")));
            }

            String callbackUrl = sandboxServer.getServerAgentUrl() + "/computer/notify-resolved";
            log.info("[ACP PERMISSION] Forwarding to {}", callbackUrl);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (sandboxServer.getConfigKey() != null) {
                headers.set("X-Nuwax-Internal-Secret", sandboxServer.getConfigKey());
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    URI.create(callbackUrl), HttpMethod.POST, entity, Map.class);

            log.info("[ACP PERMISSION] NuwaClaw responded status={}", response.getStatusCode());
            return ResponseEntity.status(response.getStatusCode()).body(response.getBody());

        } catch (Exception e) {
            log.error("[ACP PERMISSION] Forward failed, interventionId={}", interventionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("ok", false, "error", Map.of("code", "internal_error", "message", e.getMessage())));
        }
    }
}
