package com.xspaceagi.connector.ui.web.controller;

import com.xspaceagi.connector.domain.dto.ExecResultDto;
import com.xspaceagi.connector.domain.service.ConnectorExecutionService;
import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.connector.spec.exception.ConnectorException;
import com.xspaceagi.connector.ui.web.controller.dto.ExecuteRequestDto;
import com.xspaceagi.connector.ui.web.controller.dto.ProxyRequestDto;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 执行入口（设计文档 §7）：动作测试 / 运行时执行 / 通用代理。
 * 统一返回 ExecResultDto 信封；凭证明文不出模块边界。
 */
@Tag(name = "连接器执行接口")
@RestController
@RequestMapping("/api/connector")
public class ConnectorRuntimeController {

    @Resource
    private ConnectorExecutionService executionService;

    @PostMapping("/actions/{id}/test")
    @Operation(summary = "调试执行动作（需显式 connectionId）")
    public ReqResult<ExecResultDto> testAction(@PathVariable Long id,
                                               @RequestBody(required = false) Map<String, Object> body) {
        Long connectionId = body == null || body.get("connectionId") == null
                ? null : Long.valueOf(String.valueOf(body.get("connectionId")));
        if (connectionId == null) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR, "调试执行必须显式指定 connectionId");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> args = body == null || body.get("args") == null
                ? Map.of() : (Map<String, Object>) body.get("args");
        return ReqResult.success(executionService.execute(id, null, null, connectionId, args, null));
    }

    @PostMapping("/runtime/execute")
    @Operation(summary = "执行动作（Agent/运行时入口；actionId 或 providerService+actionKey）")
    public ReqResult<ExecResultDto> execute(@Valid @RequestBody ExecuteRequestDto dto) {
        if (dto.getActionId() == null
                && (StringUtils.isBlank(dto.getProviderService()) || StringUtils.isBlank(dto.getActionKey()))) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                    "actionId 与 providerService+actionKey 二选一必填");
        }
        return ReqResult.success(executionService.execute(dto.getActionId(), dto.getProviderService(),
                dto.getActionKey(), dto.getConnectionId(), dto.getArgs(), dto.getAgentId()));
    }

    @PostMapping("/proxy/{service}")
    @Operation(summary = "通用代理兜底（需 provider proxy_enabled=1；鉴权模块内注入）")
    public ReqResult<ExecResultDto> proxy(@PathVariable String service,
                                          @Valid @RequestBody ProxyRequestDto dto) {
        return ReqResult.success(executionService.proxy(service, dto.getMethod(), dto.getPath(),
                dto.getQuery(), dto.getBody(), dto.getConnectionId()));
    }
}
