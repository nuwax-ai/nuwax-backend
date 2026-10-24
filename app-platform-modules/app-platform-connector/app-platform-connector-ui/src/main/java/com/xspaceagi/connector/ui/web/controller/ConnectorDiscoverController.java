package com.xspaceagi.connector.ui.web.controller;

import com.xspaceagi.connector.domain.entity.ConnectorProvider;
import com.xspaceagi.connector.domain.service.ConnectorCatalogService;
import com.xspaceagi.connector.sdk.IConnectorApiService;
import com.xspaceagi.connector.sdk.dto.ConnectorActionGuide;
import com.xspaceagi.connector.sdk.dto.ConnectorActionInfo;
import com.xspaceagi.connector.sdk.dto.ConnectorProviderInfo;
import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.connector.spec.exception.ConnectorException;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 发现型元工具的 REST 镜像（前端连接器市场/助手面板用；Agent 侧走 SDK 门面）。
 */
@Tag(name = "连接器发现接口")
@RestController
@RequestMapping("/api/connector/discover")
public class ConnectorDiscoverController {

    @Resource
    private IConnectorApiService connectorApiService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private ConnectorCatalogService catalogService;

    @GetMapping("/providers")
    @Operation(summary = "可见连接器列表（含已连接标记）")
    public ReqResult<List<ConnectorProviderInfo>> providers(@RequestParam Long spaceId,
                                                            @RequestParam(required = false) String keyword) {
        spacePermissionService.checkSpaceUserPermission(spaceId);
        return ReqResult.success(connectorApiService.listProviders(
                RequestContext.get().getTenantId(), spaceId, RequestContext.get().getUserId(), keyword));
    }

    @GetMapping("/search")
    @Operation(summary = "跨连接器检索动作（connector_search）")
    public ReqResult<List<ConnectorActionInfo>> search(@RequestParam Long spaceId,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String tag,
                                                       @RequestParam(required = false) Integer limit) {
        spacePermissionService.checkSpaceUserPermission(spaceId);
        return ReqResult.success(connectorApiService.searchActions(
                RequestContext.get().getTenantId(), spaceId, RequestContext.get().getUserId(),
                keyword, tag, limit));
    }

    @GetMapping("/guide")
    @Operation(summary = "动作使用指南（connector_guide：入参结构+连接状态+安全须知）")
    public ReqResult<ConnectorActionGuide> guide(@RequestParam(required = false) Long spaceId,
                                                 @RequestParam String service,
                                                 @RequestParam String actionKey) {
        ConnectorProvider provider = catalogService.requireProvider(service);
        // 官方连接器全局可见、无空间归属：不校验 spaceId；空间自建连接器按所属空间校验
        if (!provider.isOfficial()) {
            Long ownerSpaceId = provider.getSpaceId();
            if (spaceId != null && !spaceId.equals(ownerSpaceId)) {
                throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                        "spaceId 与连接器所属空间不一致");
            }
            spacePermissionService.checkSpaceUserPermission(ownerSpaceId);
        }
        return ReqResult.success(connectorApiService.guideAction(
                RequestContext.get().getTenantId(), RequestContext.get().getUserId(), service, actionKey));
    }
}
