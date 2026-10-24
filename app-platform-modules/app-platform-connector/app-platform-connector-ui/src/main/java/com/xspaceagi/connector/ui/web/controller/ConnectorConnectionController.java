package com.xspaceagi.connector.ui.web.controller;

import com.xspaceagi.connector.domain.command.CreateConnectionCommand;
import com.xspaceagi.connector.domain.dto.ExecResultDto;
import com.xspaceagi.connector.domain.entity.ConnectorConnection;
import com.xspaceagi.connector.domain.entity.ConnectorProvider;
import com.xspaceagi.connector.domain.service.ConnectorCatalogService;
import com.xspaceagi.connector.domain.service.ConnectorConnectionService;
import com.xspaceagi.connector.domain.service.ConnectorExecutionService;
import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.connector.spec.exception.ConnectorException;
import com.xspaceagi.connector.ui.web.controller.dto.ConnectionDto;
import com.xspaceagi.connector.ui.web.controller.dto.CreateConnectionDto;
import com.xspaceagi.connector.ui.web.converter.ConnectorDtoConverter;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 连接管理（用户侧，个人连接 §9.2）。
 * 响应只含 profile——凭证字段绝不出现在任何返回值中。
 */
@Tag(name = "连接器连接管理接口")
@RestController
@RequestMapping("/api/connector/connections")
public class ConnectorConnectionController {

    @Resource
    private ConnectorConnectionService connectionService;

    @Resource
    private ConnectorExecutionService executionService;

    @Resource
    private ConnectorCatalogService catalogService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @PostMapping("/api-key")
    @Operation(summary = "直填 API Key/Bearer/custom 字段建连接")
    public ReqResult<ConnectionDto> createDirect(@Valid @RequestBody CreateConnectionDto dto) {
        ConnectorProvider provider = catalogService.requireProvider(dto.getProviderService());
        // 归属空间以 provider 为准，不信任请求体：
        // 官方连接器全局可见、无空间归属，连接落 spaceId=0（对齐官方语义，避免 NOT NULL 违约）；
        // 空间自建连接器的连接只能落在它所属的空间，客户端可以不传 spaceId（由 provider 推导），
        // 传了则必须与 provider 归属空间一致——否则连接会被写进另一个空间。
        Long spaceId;
        if (provider.isOfficial()) {
            spaceId = 0L;
        } else {
            Long providerSpaceId = provider.getSpaceId();
            if (providerSpaceId == null || providerSpaceId <= 0) {
                throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                        "连接器归属空间非法：空间自建连接器的 provider.spaceId 须 >0");
            }
            if (dto.getSpaceId() != null && !dto.getSpaceId().equals(providerSpaceId)) {
                throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                        "spaceId 与连接器归属空间不一致：连接只能建在连接器所属空间内");
            }
            spacePermissionService.checkSpaceUserPermission(providerSpaceId);
            spaceId = providerSpaceId;
        }
        CreateConnectionCommand command = new CreateConnectionCommand();
        command.setSpaceId(spaceId);
        command.setProviderService(dto.getProviderService());
        command.setName(dto.getName());
        command.setFields(dto.getFields());
        ConnectorConnection connection = connectionService.createDirectConnection(
                RequestContext.get().getTenantId(), RequestContext.get().getUserId(), command);
        return ReqResult.success(toDto(connection, displayNames(List.of(connection))));
    }

    @GetMapping
    @Operation(summary = "我的连接列表（只含 profile）")
    public ReqResult<List<ConnectionDto>> listMine(@RequestParam(required = false) String service,
                                                   @RequestParam(required = false) Long spaceId) {
        if (spaceId != null) {
            spacePermissionService.checkSpaceUserPermission(spaceId);
        }
        List<ConnectorConnection> connections = connectionService.listMyConnections(
                RequestContext.get().getTenantId(), RequestContext.get().getUserId(), service);
        Map<String, String> displayNames = displayNames(connections);
        return ReqResult.success(connections.stream()
                .map(connection -> toDto(connection, displayNames))
                .collect(Collectors.toList()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "连接详情（只含 profile）")
    public ReqResult<ConnectionDto> detail(@PathVariable Long id) {
        ConnectorConnection connection = connectionService.requireOwned(id, RequestContext.get().getUserId());
        return ReqResult.success(toDto(connection, displayNames(List.of(connection))));
    }

    @PostMapping("/{id}/verify")
    @Operation(summary = "验证连接：执行 whoami 类动作并尽力回填 profile")
    public ReqResult<ExecResultDto> verify(@PathVariable Long id) {
        return ReqResult.success(executionService.verifyConnection(id));
    }

    @PostMapping("/{id}/status")
    @Operation(summary = "启用/停用连接（只写 enabled 开关，不改动凭证健康度 status；停用后凭证保留、"
            + "连接仍在列表与目录里可见，但不参与执行解析，随时可启用回来）")
    public ReqResult<ConnectionDto> setStatus(@PathVariable Long id,
                                             @RequestParam(defaultValue = "true") boolean enabled) {
        ConnectorConnection connection = connectionService.setEnabled(
                id, RequestContext.get().getUserId(), enabled);
        return ReqResult.success(toDto(connection, displayNames(List.of(connection))));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "断开连接（凭证连同 credential 行删除）")
    public ReqResult<Void> disconnect(@PathVariable Long id) {
        connectionService.disconnect(id, RequestContext.get().getUserId());
        return ReqResult.success();
    }

    // ---------- 内部 ----------

    private ConnectionDto toDto(ConnectorConnection connection, Map<String, String> displayNames) {
        ConnectionDto dto = new ConnectionDto();
        dto.setId(connection.getId());
        dto.setSpaceId(connection.getSpaceId());
        dto.setProviderService(connection.getProviderService());
        dto.setProviderDisplayName(displayNames.getOrDefault(connection.getProviderService(),
                connection.getProviderService()));
        dto.setName(connection.getName());
        dto.setStatus(connection.getStatus());
        dto.setEnabled(connection.getEnabled());
        dto.setProfile(ConnectorDtoConverter.parseJson(connection.getProfile()));
        dto.setIdentity(ConnectorDtoConverter.readIdentity(connection));
        dto.setGrantedScopes(ConnectorDtoConverter.parseStringList(connection.getGrantedScopes()));
        dto.setModified(connection.getModified());
        dto.setCreated(connection.getCreated());
        return dto;
    }

    private Map<String, String> displayNames(List<ConnectorConnection> connections) {
        Map<String, String> names = new HashMap<>();
        for (ConnectorConnection connection : connections) {
            String service = connection.getProviderService();
            if (names.containsKey(service)) {
                continue;
            }
            try {
                ConnectorProvider provider = catalogService.requireProvider(service);
                names.put(service, provider.getDisplayName());
            } catch (Exception e) {
                names.put(service, service);
            }
        }
        return names;
    }
}
