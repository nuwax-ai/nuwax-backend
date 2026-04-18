package com.xspaceagi.agent.core.infra.rpc;

import com.xspaceagi.agent.core.spec.utils.CopyRelationCacheUtil;
import com.xspaceagi.mcp.sdk.IMcpApiService;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.mcp.sdk.dto.McpExecuteOutput;
import com.xspaceagi.mcp.sdk.dto.McpExecuteRequest;
import com.xspaceagi.mcp.sdk.dto.McpToolDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class McpRpcService {

    @Resource
    private IMcpApiService iMcpApiService;

    @Value("${platform.base-url:}")
    private String platformBaseUrl;

    public McpDto queryMcp(Long id, Long spaceId) {
        Object mcp = SimpleJvmHashCache.getHash("mcp_deployed", id.toString());
        if (mcp instanceof McpDto) {
            return (McpDto) JsonSerializeUtil.deepCopy(mcp);
        }
        mcp = iMcpApiService.getDeployedMcp(id, spaceId);
        if (mcp == null) {
            return null;
        }
        SimpleJvmHashCache.putHash("mcp_deployed", id.toString(), mcp, 5);//jvm缓存5秒
        return (McpDto) JsonSerializeUtil.deepCopy(mcp);
    }

    public void checkMcpPermission(McpDto deployedMcp, String toolName) {
        if (deployedMcp == null || StringUtils.isBlank(toolName) || deployedMcp.getDeployedConfig() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentMcpServiceNotFoundOrDenied);
        }
        McpToolDto mcpToolDto = deployedMcp.getDeployedConfig().getTools().stream()
                .filter(tool -> tool.getName().equals(toolName))
                .findFirst().orElse(null);
        if (mcpToolDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentMcpToolNotFoundOrDenied);
        }
    }

    public Flux<McpExecuteOutput> execute(McpExecuteRequest mcpExecuteRequestDto) {
        return iMcpApiService.execute(mcpExecuteRequestDto);
    }

    public Long addAndDeployMcp(Long userId, Long targetSpaceId, McpDto mcpDto) {
        mcpDto.setCreated(null);
        mcpDto.setModified(null);
        Long originalMcpId = mcpDto.getId();
        String key = "mcp";
        if (RequestContext.get() != null && RequestContext.get().getRequestId() != null) {
            key = key + ":" + RequestContext.get().getRequestId();
        }
        Object value = CopyRelationCacheUtil.get(key, targetSpaceId, originalMcpId);
        if (value != null) {
            return (Long) value;
        }
        Long newMcpId = iMcpApiService.addAndDeployMcp(userId, targetSpaceId, mcpDto);
        CopyRelationCacheUtil.put(key, targetSpaceId, originalMcpId, newMcpId);
        return newMcpId;
    }

    public Long deployProxyMcp(McpDto mcpDto) {
        return iMcpApiService.deployProxyMcp(mcpDto);
    }

    public String getExportMcpServerConfig(Long userId, Long mcpId) {
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        String siteUrl = tenantConfigDto.getSiteUrl().trim().endsWith("/") ? tenantConfigDto.getSiteUrl() : tenantConfigDto.getSiteUrl() + "/";
        String exportMcpServerConfig = iMcpApiService.getExportMcpServerConfig(userId, mcpId);
        if (StringUtils.isNotBlank(platformBaseUrl)) {
            platformBaseUrl = platformBaseUrl.trim().endsWith("/") ? platformBaseUrl : platformBaseUrl + "/";
            exportMcpServerConfig = exportMcpServerConfig.replace(siteUrl, platformBaseUrl);
        } else if (StringUtils.isNotBlank(tenantConfigDto.getSiteConfigUrl())) {
            String siteConfigUrl = tenantConfigDto.getSiteConfigUrl().trim().endsWith("/") ? tenantConfigDto.getSiteConfigUrl() : tenantConfigDto.getSiteConfigUrl() + "/";
            exportMcpServerConfig = exportMcpServerConfig.replace(siteUrl, siteConfigUrl);
        }
        return exportMcpServerConfig;
    }
}
