package com.xspaceagi.connector.ui.web.controller;

import com.xspaceagi.agent.core.sdk.IPluginWorkflowRpcService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 连接器工具绑定插件/工作流（选择器数据源经 agent SPI 提供）。
 * <p>
 * 空间端传当前 spaceId；管理端（官方工具维护）不传 spaceId，由 SPI 取当前用户主空间已发布列表。
 */
@Tag(name = "连接器工具绑定相关接口")
@RestController
@RequestMapping("/api/connector/bindable")
public class ConnectorBindingController {

    @Resource
    private ObjectProvider<IPluginWorkflowRpcService> bindingSpiProvider;

    @GetMapping
    @Operation(summary = "可绑定插件/工作流列表（仅已发布；spaceId 为空取当前用户主空间）")
    public ReqResult<List<Map<String, Object>>> list(@RequestParam String type,
                                                     @RequestParam(required = false) Long spaceId) {
        IPluginWorkflowRpcService bindingSpi = bindingSpiProvider.getIfAvailable();
        if (bindingSpi == null) {
            return ReqResult.error("插件/工作流绑定能力未启用");
        }
        String bindType = "plugin".equalsIgnoreCase(type) ? "PLUGIN" : "WORKFLOW";
        return ReqResult.success(bindingSpi.listBindable(bindType, spaceId, RequestContext.get().getUserId()));
    }
}
