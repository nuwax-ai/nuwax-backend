package com.xspaceagi.connector.ui.web.controller;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.connector.domain.command.ActionCommand;
import com.xspaceagi.connector.domain.command.ProviderCommand;
import com.xspaceagi.connector.domain.entity.ConnectorAction;
import com.xspaceagi.connector.domain.entity.ConnectorProvider;
import com.xspaceagi.connector.domain.entity.ConnectorRunLog;
import com.xspaceagi.connector.domain.service.ConnectorCatalogService;
import com.xspaceagi.connector.domain.service.ConnectorImportService;
import com.xspaceagi.connector.domain.service.ConnectorOAuthService;
import com.xspaceagi.connector.infra.repository.ConnectorActionRepository;
import com.xspaceagi.connector.infra.repository.ConnectorProviderRepository;
import com.xspaceagi.connector.infra.repository.ConnectorRunLogRepository;
import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.connector.spec.exception.ConnectorException;
import com.xspaceagi.connector.ui.web.controller.dto.ActionDto;
import com.xspaceagi.connector.ui.web.controller.dto.ActionSaveDto;
import com.xspaceagi.connector.ui.web.controller.dto.OauthConfigSaveDto;
import com.xspaceagi.connector.ui.web.controller.dto.ProviderDto;
import com.xspaceagi.connector.ui.web.controller.dto.ProviderExportDto;
import com.xspaceagi.connector.ui.web.controller.dto.ProviderOrderDto;
import com.xspaceagi.connector.ui.web.controller.dto.ProviderSaveDto;
import com.xspaceagi.connector.ui.web.converter.ConnectorDtoConverter;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 连接器管理侧接口（平台级治理：启停/deprecated/审计，设计文档 §7）。
 * <p>
 * 连接器与 MCP 模块无关，不复用 MCP 权限资源码；连接器自己的权限资源码体系
 * 尚未建立，管理侧门禁暂不挂（待权限设计补齐后再挂）。
 */
@Tag(name = "连接器管理侧接口")
@RestController
@RequestMapping("/api/system/connector")
public class ConnectorSystemController {

    @Resource
    private ConnectorCatalogService catalogService;

    @Resource
    private ConnectorOAuthService oauthService;

    @Resource
    private ConnectorRunLogRepository runLogRepository;

    @Resource
    private ConnectorProviderRepository providerRepository;

    @Resource
    private ConnectorActionRepository actionRepository;

    @Resource
    private ConnectorImportService importService;

    @PutMapping("/providers/{service}")
    @Operation(summary = "启停 provider")
    public ReqResult<Void> setProviderStatus(@PathVariable String service,
                                             @RequestParam boolean enabled) {
        catalogService.setProviderStatus(service, enabled);
        return ReqResult.success();
    }

    @PutMapping("/actions/{id}")
    @Operation(summary = "标记/取消 deprecated（不删、停新绑定、执行带告警）")
    public ReqResult<Void> setActionDeprecated(@PathVariable Long id,
                                               @RequestParam boolean deprecated) {
        catalogService.setActionDeprecated(id, deprecated);
        return ReqResult.success();
    }

    @PostMapping("/oauth-config")
    @Operation(summary = "保存平台公共 OAuth App 配置（client_secret 加密落库）")
    public ReqResult<Void> savePlatformOauthConfig(@Valid @RequestBody OauthConfigSaveDto dto) {
        oauthService.saveConfig(dto.getService(), "platform", 0L, dto.getClientId(),
                dto.getClientSecret(), dto.getAuthUrl(), dto.getTokenUrl(), dto.getScopes());
        return ReqResult.success();
    }

    @GetMapping("/oauth-config")
    @Operation(summary = "查看平台公共 OAuth App 配置（client_secret 脱敏）")
    public ReqResult<ConnectorOAuthService.OauthConfigView> getPlatformOauthConfig(
            @RequestParam String service) {
        return ReqResult.success(oauthService.getConfig(service, "platform", 0L));
    }

    @GetMapping("/runs")
    @Operation(summary = "执行审计查询")
    public ReqResult<IPage<ConnectorRunLog>> pageRuns(@RequestParam(required = false) Long connectionId,
                                                      @RequestParam(required = false) Long userId,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(required = false, defaultValue = "1") long pageNum,
                                                      @RequestParam(required = false, defaultValue = "20") long pageSize) {
        return ReqResult.success(runLogRepository.page(connectionId, userId, status, pageNum, pageSize));
    }

    // ---------- 官方目录维护（管理侧；创建入口 POST /providers，其余维护字段/动作） ----------
    // 全部先过 requireOfficialProvider：空间自建条目（space_id≠0）拒绝，提示走空间管理接口。

    @GetMapping("/providers")
    @Operation(summary = "官方目录全量（含停用，按 sortOrder 排序，附工具数；可按 category 过滤）")
    public ReqResult<List<ProviderDto>> listOfficialProviders(
            @RequestParam(required = false) String category) {
        List<ConnectorProvider> list = providerRepository.listAllOfficial(category);
        Map<String, Long> counts = actionRepository.countByServices(
                list.stream().map(ConnectorProvider::getService).collect(Collectors.toList()));
        return ReqResult.success(list.stream().map(provider -> {
            ProviderDto dto = ConnectorDtoConverter.toProviderDto(provider);
            Long cnt = counts.get(provider.getService());
            if (cnt != null) dto.setActionCount(cnt.intValue());
            return dto;
        }).collect(Collectors.toList()));
    }

    @PutMapping("/providers/order")
    @Operation(summary = "保存官方目录排序（services 为官方全量有序数组，索引即顺序，越小越靠前）")
    public ReqResult<Void> saveOfficialOrder(@RequestBody ProviderOrderDto dto) {
        List<String> services = dto.getServices();
        if (services == null || services.isEmpty()) {
            throw new ConnectorException(ConnectorErrorCode.IMPORT_BUNDLE_INVALID, "排序 service 列表不能为空");
        }
        Map<String, Integer> order = new LinkedHashMap<>();
        int seq = 1;
        for (String service : services) {
            order.put(service, seq++);
        }
        providerRepository.batchUpdateSortOrder(order);
        return ReqResult.success();
    }

    @PostMapping("/providers/export")
    @Operation(summary = "管理侧导出官方包（单/多/全量；services 空/缺省=全部官方；仅元数据不含凭证）")
    public ReqResult<JSONObject> exportOfficialProviders(@RequestBody(required = false) ProviderExportDto dto) {
        List<String> effective;
        if (dto == null || dto.getServices() == null || dto.getServices().isEmpty()) {
            effective = providerRepository.listAllOfficial().stream()
                    .map(ConnectorProvider::getService).collect(Collectors.toList());
        } else {
            effective = dto.getServices();
        }
        return ReqResult.success(importService.exportByServices(effective));
    }

    @PostMapping("/providers")
    @Operation(summary = "管理侧创建官方全局 provider（space_id=0、managed_by=system、全员可见）")
    public ReqResult<ProviderDto> createOfficialProvider(@Valid @RequestBody ProviderSaveDto dto) {
        ConnectorProvider created = catalogService.createSystemProvider(
                RequestContext.get().getTenantId(), toProviderCommand(dto));
        return ReqResult.success(ConnectorDtoConverter.toProviderDto(created));
    }

    @PutMapping("/providers/{service}/meta")
    @Operation(summary = "更新官方 provider 基本信息/认证配置/描述/App 模式（保持 managed_by=system）")
    public ReqResult<ProviderDto> updateOfficialProvider(@PathVariable String service,
                                                         @Valid @RequestBody ProviderSaveDto dto) {
        requireOfficialProvider(service);
        ConnectorProvider updated = catalogService.updateSystemProvider(service, toProviderCommand(dto));
        return ReqResult.success(ConnectorDtoConverter.toProviderDto(updated));
    }

    @DeleteMapping("/providers/{service}")
    @Operation(summary = "删除官方连接器（含其全部动作；存在连接时拒绝删除）")
    public ReqResult<Void> deleteOfficialProvider(@PathVariable String service) {
        requireOfficialProvider(service);
        catalogService.deleteProvider(service);
        return ReqResult.success();
    }

    @PostMapping("/providers/{service}/actions")
    @Operation(summary = "新增官方动作（managed_by=system，不覆盖手工保护区条目）")
    public ReqResult<ActionDto> createOfficialAction(@PathVariable String service,
                                                     @Valid @RequestBody ActionSaveDto dto) {
        requireOfficialProvider(service);
        return ReqResult.success(ConnectorDtoConverter.toActionDto(
                catalogService.saveAction(toSystemActionCommand(service, dto))));
    }

    @PutMapping("/providers/{service}/actions/{actionKey}")
    @Operation(summary = "更新官方动作（actionKey 以路径为准；managed_by=system，不覆盖手工保护区条目）")
    public ReqResult<ActionDto> updateOfficialAction(@PathVariable String service,
                                                     @PathVariable String actionKey,
                                                     @Valid @RequestBody ActionSaveDto dto) {
        requireOfficialProvider(service);
        // 校验动作存在且归属该官方 provider（404 语义）
        catalogService.requireActionByKey(service, actionKey);
        ActionCommand command = toSystemActionCommand(service, dto);
        command.setActionKey(actionKey);
        return ReqResult.success(ConnectorDtoConverter.toActionDto(catalogService.saveAction(command)));
    }

    @DeleteMapping("/actions/{id}")
    @Operation(summary = "删除官方动作")
    public ReqResult<Void> deleteOfficialAction(@PathVariable Long id) {
        ConnectorAction action = catalogService.requireAction(id);
        requireOfficialProvider(action.getProviderService());
        catalogService.deleteAction(id);
        return ReqResult.success();
    }

    @PutMapping("/actions/{id}/status")
    @Operation(summary = "启停用官方动作")
    public ReqResult<Void> setOfficialActionStatus(@PathVariable Long id,
                                                   @RequestParam(defaultValue = "true") boolean enabled) {
        ConnectorAction action = catalogService.requireAction(id);
        requireOfficialProvider(action.getProviderService());
        catalogService.setActionStatus(id, enabled);
        return ReqResult.success();
    }

    // ---------- 内部 ----------

    private ConnectorProvider requireOfficialProvider(String service) {
        ConnectorProvider provider = catalogService.requireProvider(service);
        if (provider.getSpaceId() != null && provider.getSpaceId() != 0L) {
            throw new ConnectorException(ConnectorErrorCode.PROTECTED_ZONE,
                    "空间自建连接器请走空间管理接口维护: " + service);
        }
        return provider;
    }

    private ProviderCommand toProviderCommand(ProviderSaveDto dto) {
        ProviderCommand command = new ProviderCommand();
        command.setService(dto.getService());
        command.setDisplayName(dto.getDisplayName());
        command.setDescription(dto.getDescription());
        command.setIcon(dto.getIcon());
        command.setCategory(dto.getCategory());
        command.setTags(dto.getTags());
        command.setAuthType(dto.getAuthType());
        command.setBaseUrl(dto.getBaseUrl());
        command.setAuthConfig(dto.getAuthConfig());
        command.setOauthAppMode(dto.getOauthAppMode());
        command.setProxyEnabled(dto.getProxyEnabled());
        return command;
    }

    private ActionCommand toSystemActionCommand(String service, ActionSaveDto dto) {
        ActionCommand command = new ActionCommand();
        command.setProviderService(service);
        command.setActionKey(dto.getActionKey());
        command.setName(dto.getName());
        command.setDescription(dto.getDescription());
        command.setTags(dto.getTags());
        command.setInputArgs(dto.getInputArgs());
        command.setOutputArgs(dto.getOutputArgs());
        command.setExecType(dto.getExecType());
        command.setExecRef(dto.getExecRef());
        command.setHttpSpec(dto.getHttpSpec());
        // 系统级写入：managed_by=system，且不覆盖手工保护区（user）条目，与导入管线同语义
        command.setSystemLevel(true);
        return command;
    }
}
