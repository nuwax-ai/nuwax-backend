package com.xspaceagi.agent.core.infra.rpc;

import com.xspaceagi.sandbox.sdk.server.ISandboxConfigRpcService;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.sandbox.sdk.support.SandboxEndpointConverter;
import com.xspaceagi.sandbox.sdk.support.SandboxEndpointConverter.SandboxEndpoint;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 沙箱端点解析入口（RPC 编排 + 属主校验 + 日志）：
 * URL 组装与可用性校验在 sandbox-sdk 的 {@link SandboxEndpointConverter}（sandbox 上下文所有，非 agent 消费者可直接复用），
 * 本类只保留需要注入 RPC 与租户红线的部分。
 *
 * 端点寻址地图（按寻址键）：
 * - cId（会话）        → SandboxServerConfigService.selectServer（不经本类）
 * - projectId（pageApp）→ {@link #resolvePageAppSandbox}（本类）
 * - sandboxId          → {@link #resolveBySandboxIdForOwner}（本类；USER 级含属主校验，租户红线）
 * - userapp dev/prod   → UserAppEndpointResolver（userapp 模块统一入口，选路只此一处）
 */
@Slf4j
@Component
public class SandboxConfigEndpoints {

    @Resource
    private ISandboxConfigRpcService iSandboxConfigRpcService;

    /**
     * 按沙箱ID解析并做属主校验：queryById 免租户直查，租户隔离完全依赖此处 USER 级属主比对（fail-closed，不可移除）；
     * 配置不存在 / 属主不匹配 / 配置不可用均返回 null，由调用方决定抛错或回退。
     * 注：SandboxConfigRpcDto 不含租户字段，属主比对即租户隔离的充分条件（USER 级沙箱只属于一个用户）。
     */
    public SandboxEndpoint resolveBySandboxIdForOwner(Long sandboxId, Long expectedUserId) {
        if (sandboxId == null) {
            return null;
        }
        SandboxConfigRpcDto dto = iSandboxConfigRpcService.queryById(sandboxId);
        if (dto == null) {
            log.warn("[SandboxEndpoints] sandbox config not found, sandboxId={}", sandboxId);
            return null;
        }
        // null-safe：user_id 列可空（系统上下文创建时回填 null），null 视为不匹配（fail-closed）
        if (dto.getScope() == SandboxScopeEnum.USER && !Objects.equals(dto.getUserId(), expectedUserId)) {
            log.warn("[SandboxEndpoints] sandbox config owner mismatch, sandboxId={}, configUser={}, expectedUser={}",
                    sandboxId, dto.getUserId(), expectedUserId);
            return null;
        }
        SandboxEndpoint endpoint = SandboxEndpointConverter.toEndpoint(dto);
        if (endpoint == null) {
            log.warn("[SandboxEndpoints] sandbox unusable, sandboxId={}, active={}", sandboxId, dto.getIsActive());
        }
        return endpoint;
    }

    /**
     * 网页应用（PageApp）项目绑定沙箱解析（custompage 项目直连其绑定的开发沙箱）：
     * sandboxId 为 null 返回 null（调用方走兜底配置）；查不到抛 agentSandboxNotFound、配置不可用抛 agentSandboxConfigError
     * （与原 GitRpcClient/ComputerPodClient 的错误口径一致）。
     */
    public SandboxEndpoint resolvePageAppSandbox(Long tenantId, Long userId, Long spaceId, Long projectId, Long sandboxId) {
        if (sandboxId == null) {
            return null;
        }
        SandboxConfigRpcDto dto = iSandboxConfigRpcService.selectAppDevelopmentSandbox(tenantId, userId, spaceId, projectId, sandboxId);
        if (dto == null || dto.getConfigValue() == null) {
            throw BizException.of(BizExceptionCodeEnum.agentSandboxNotFound);
        }
        SandboxEndpoint endpoint = SandboxEndpointConverter.toEndpoint(dto);
        if (endpoint == null) {
            throw BizException.of(BizExceptionCodeEnum.agentSandboxConfigError);
        }
        return endpoint;
    }
}
