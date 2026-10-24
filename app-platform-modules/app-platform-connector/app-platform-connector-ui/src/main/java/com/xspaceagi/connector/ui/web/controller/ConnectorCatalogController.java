package com.xspaceagi.connector.ui.web.controller;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.connector.domain.command.ActionCommand;
import com.xspaceagi.connector.domain.command.ProviderCommand;
import com.xspaceagi.connector.domain.dto.ImportReportDto;
import com.xspaceagi.connector.domain.entity.ConnectorAction;
import com.xspaceagi.connector.domain.entity.ConnectorConnection;
import com.xspaceagi.connector.domain.entity.ConnectorProvider;
import com.xspaceagi.connector.domain.service.ConnectorCatalogService;
import com.xspaceagi.connector.domain.service.ConnectorImportService;
import com.xspaceagi.connector.infra.repository.ConnectorActionRepository;
import com.xspaceagi.connector.infra.repository.ConnectorConnectionRepository;
import com.xspaceagi.connector.spec.enums.ConnectionStatusEnum;
import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.connector.spec.exception.ConnectorException;
import com.xspaceagi.connector.ui.web.controller.dto.ActionDto;
import com.xspaceagi.connector.ui.web.controller.dto.ActionSaveDto;
import com.xspaceagi.connector.ui.web.controller.dto.ImportApplyDto;
import com.xspaceagi.connector.ui.web.controller.dto.ProviderDetailDto;
import com.xspaceagi.connector.ui.web.controller.dto.ProviderDto;
import com.xspaceagi.connector.ui.web.controller.dto.ProviderSaveDto;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 连接器目录与创建（控制台/用户侧，设计文档 §7）。
 * <p>
 * 权限模型（§4.8）：创建/编辑/删除挂空间成员权限；官方目录（space_id=0）
 * 只能经导入管线与管理侧接口维护，用户侧接口拒绝写官方条目。
 */
@Tag(name = "连接器目录相关接口")
@RestController
@RequestMapping("/api/connector")
public class ConnectorCatalogController {

    @Resource
    private ConnectorCatalogService catalogService;

    @Resource
    private ConnectorImportService importService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private ConnectorConnectionRepository connectionRepository;

    @Resource
    private ConnectorActionRepository actionRepository;

    // ---------- 查询 ----------

    @GetMapping("/providers")
    @Operation(summary = "目录列表（分页/分类/tags/搜索；scope=official 仅官方、scope=space 仅空间自建含 disabled；" +
            "connected=connected|unconnected 按「是否建过连接」过滤；" +
            "connectionEnabled=true|false 按「我是否启用了该连接器」过滤；status=enabled|disabled|all；" +
            "spaceId 缺省=用户全部空间，scope=official 时忽略；" +
            "每条带 connected 与 connectionEnabled 两个标注，已连接时另带 connectionId 与 connections（连接 id 明细）；" +
            "排序固定为 可用 > 已启用但凭证异常 > 已连接未启用 > 未连接，档内 sort_order,id）")
    public ReqResult<IPage<ProviderDto>> listProviders(@RequestParam(required = false) Long spaceId,
                                                       @RequestParam(required = false) String category,
                                                       @RequestParam(required = false) String tag,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String scope,
                                                       @RequestParam(required = false, defaultValue = "enabled") String status,
                                                       @RequestParam(required = false) String connected,
                                                       @RequestParam(required = false) Boolean connectionEnabled,
                                                       @RequestParam(required = false, defaultValue = "1") long pageNum,
                                                       @RequestParam(required = false, defaultValue = "20") long pageSize) {
        Long tenantId = RequestContext.get().getTenantId();
        Long userId = RequestContext.get().getUserId();
        // spaceId 可选：传了就是单空间视图（校验该空间成员权限）；没传则按「用户全部空间」查询。
        // 这不是放宽鉴权——空间列表取自用户自己的权限范围，反而比要求调用方自报 spaceId 更可靠。
        // scope=official 只查官方目录（space_id=0 全局可见），连空间列表都不必取。
        List<Long> spaceIds = List.of();
        if (!"official".equals(scope)) {
            if (spaceId != null) {
                spacePermissionService.checkSpaceUserPermission(spaceId);
                spaceIds = List.of(spaceId);
            } else {
                spaceIds = spacePermissionService.querySpaceIdList(userId);
            }
        }

        MyConnections mine = connectionsOfUser(tenantId, userId);
        // connected 参数兼容：true→已连接；false/缺省→不过滤；connected/unconnected→三态
        String connectedFilter = null;
        if ("true".equalsIgnoreCase(connected) || "connected".equalsIgnoreCase(connected)) {
            connectedFilter = "connected";
        } else if ("unconnected".equalsIgnoreCase(connected)) {
            connectedFilter = "unconnected";
        }
        // connectionEnabled：true→只看我启用的；false→只看我没启用的；缺省（null）→不过滤
        String connectionEnabledFilter = connectionEnabled == null
                ? null : (connectionEnabled ? "enabled" : "disabled");
        IPage<ConnectorProvider> page = catalogService.pageProviders(tenantId, userId, spaceIds, category, tag,
                keyword, connectedFilter, mine.connectedServices(), connectionEnabledFilter, mine.enabledServices(),
                scope, status, pageNum, pageSize);

        List<ConnectorProvider> records = page.getRecords();
        Map<String, Long> counts = actionRepository.countByServices(
                records.stream().map(ConnectorProvider::getService).collect(Collectors.toList()));
        Page<ProviderDto> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(records.stream().map(provider -> {
            ProviderDto dto = ConnectorDtoConverter.toProviderDto(provider);
            fillConnectionFlags(dto, provider.getService(), mine);
            Long cnt = counts.get(provider.getService());
            if (cnt != null) dto.setActionCount(cnt.intValue());
            return dto;
        }).collect(Collectors.toList()));
        return ReqResult.success(result);
    }

    @GetMapping("/providers/overview")
    @Operation(summary = "总览：当前用户全部可见连接器（官方 enabled + 我有权限空间的自建 enabled），不分页，前端分组排序；" +
            "scope=official 仅官方、scope=space 仅空间自建；每条带 connected 与 connectionEnabled 标注，" +
            "已连接时另带 connectionId 与 connections（连接 id 明细）；" +
            "排序同 /providers（可用 > 已启用但凭证异常 > 已连接未启用 > 未连接）")
    public ReqResult<List<ProviderDto>> listOverview(
            @RequestParam(required = false) String scope) {
        Long tenantId = RequestContext.get().getTenantId();
        Long userId = RequestContext.get().getUserId();
        List<Long> spaceIds = spacePermissionService.querySpaceIdList(userId);
        List<ConnectorProvider> providers = catalogService.listProvidersForOverview(
                tenantId, userId, spaceIds, "enabled", scope);
        MyConnections mine = connectionsOfUser(tenantId, userId);
        Map<String, Long> counts = actionRepository.countByServices(
                providers.stream().map(ConnectorProvider::getService).collect(Collectors.toList()));
        List<ProviderDto> list = providers.stream().map(provider -> {
            ProviderDto dto = ConnectorDtoConverter.toProviderDto(provider);
            fillConnectionFlags(dto, provider.getService(), mine);
            Long cnt = counts.get(provider.getService());
            if (cnt != null) dto.setActionCount(cnt.intValue());
            return dto;
        }).collect(Collectors.toList());
        return ReqResult.success(list);
    }

    @GetMapping("/providers/{service}")
    @Operation(summary = "provider 详情 + action 列表")
    public ReqResult<ProviderDetailDto> providerDetail(@PathVariable String service,
                                                       @RequestParam(required = false) Long spaceId,
                                                       @RequestParam(required = false, defaultValue = "false") boolean includeDeprecated,
                                                       @RequestParam(required = false, defaultValue = "false") boolean includeDisabled) {
        ConnectorProvider provider = catalogService.requireProvider(service);
        Long ownerSpaceId = provider.getSpaceId();
        boolean official = ownerSpaceId == null || ownerSpaceId == 0L;
        if (!official) {
            // 空间自建连接器：必须校验用户对「连接器所属空间」的权限（而不是任意空间）。
            // 传入 spaceId 仅作一致性校验，缺省或一致即可，最终以 ownerSpaceId 为准。
            if (spaceId != null && !spaceId.equals(ownerSpaceId)) {
                throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR,
                        "spaceId 与连接器所属空间不一致");
            }
            spacePermissionService.checkSpaceUserPermission(ownerSpaceId);
        }
        // 官方连接器（space_id=0/null）：全局可见，无需空间权限。
        // 空间自建 provider（spaceId≠0）的动作抽屉保留停用条目以便重新启用；
        // 管理侧（includeDisabled=true）同样展示官方已停用动作以便重启用（官方缺省仍仅 enabled）。
        // 直接走 repository（showDisabled 参数），域服务 listActions 保持不含 disabled，执行校验路径零影响。
        boolean showDisabled = includeDisabled || (provider.getSpaceId() != null && provider.getSpaceId() != 0L);
        List<ConnectorAction> actions = actionRepository.listByProvider(service, null, null, includeDeprecated, showDisabled);

        ProviderDetailDto detail = new ProviderDetailDto();
        ProviderDto providerDto = ConnectorDtoConverter.toProviderDto(provider);
        fillConnectionFlags(providerDto, service, connectionsOfUser(RequestContext.get().getTenantId(),
                RequestContext.get().getUserId()));
        providerDto.setActionCount(actions.size());
        detail.setProvider(providerDto);
        detail.setActions(actions.stream().map(ConnectorDtoConverter::toActionDto).collect(Collectors.toList()));
        return ReqResult.success(detail);
    }

    // ---------- 用户/空间侧创建（§4.8 仅声明式） ----------

    @PostMapping("/providers")
    @Operation(summary = "创建声明式连接器（空间自定义层）")
    public ReqResult<ProviderDto> createProvider(@Valid @RequestBody ProviderSaveDto dto) {
        if (dto.getSpaceId() == null) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR, "spaceId 必填");
        }
        spacePermissionService.checkSpaceUserPermission(dto.getSpaceId());
        ProviderCommand command = toProviderCommand(dto);
        ConnectorProvider provider = catalogService.createProvider(
                RequestContext.get().getTenantId(), dto.getSpaceId(), command);
        return ReqResult.success(ConnectorDtoConverter.toProviderDto(provider));
    }

    @PutMapping("/providers/{service}")
    @Operation(summary = "编辑连接器（改过即 managed_by=user 进保护区）")
    public ReqResult<ProviderDto> updateProvider(@PathVariable String service,
                                                 @Valid @RequestBody ProviderSaveDto dto) {
        ConnectorProvider provider = requireSpaceManagedProvider(service);
        spacePermissionService.checkSpaceAdminPermission(provider.getSpaceId());
        ConnectorProvider updated = catalogService.updateProvider(service, toProviderCommand(dto));
        return ReqResult.success(ConnectorDtoConverter.toProviderDto(updated));
    }

    @DeleteMapping("/providers/{service}")
    @Operation(summary = "删除连接器（校验无存量连接）")
    public ReqResult<Void> deleteProvider(@PathVariable String service) {
        ConnectorProvider provider = requireSpaceManagedProvider(service);
        spacePermissionService.checkSpaceAdminPermission(provider.getSpaceId());
        catalogService.deleteProvider(service);
        return ReqResult.success();
    }

    @PostMapping("/providers/{service}/status")
    @Operation(summary = "启停用连接器（仅空间自建；官方由管理端治理）")
    public ReqResult<Void> setProviderStatus(@PathVariable String service,
                                             @RequestParam(defaultValue = "true") boolean enabled) {
        ConnectorProvider provider = requireSpaceManagedProvider(service);
        spacePermissionService.checkSpaceAdminPermission(provider.getSpaceId());
        catalogService.setProviderStatus(service, enabled);
        return ReqResult.success();
    }

    @PostMapping("/providers/{service}/actions")
    @Operation(summary = "为空间连接器新增声明式动作")
    public ReqResult<ActionDto> createAction(@PathVariable String service, @Valid @RequestBody ActionSaveDto dto) {
        ConnectorProvider provider = requireSpaceManagedProvider(service);
        spacePermissionService.checkSpaceAdminPermission(provider.getSpaceId());
        ActionCommand command = toActionCommand(service, dto);
        return ReqResult.success(ConnectorDtoConverter.toActionDto(catalogService.saveAction(command)));
    }

    @PutMapping("/actions/{id}")
    @Operation(summary = "编辑动作")
    public ReqResult<ActionDto> updateAction(@PathVariable Long id, @Valid @RequestBody ActionSaveDto dto) {
        ConnectorAction action = catalogService.requireAction(id);
        ConnectorProvider provider = requireSpaceManagedProvider(action.getProviderService());
        spacePermissionService.checkSpaceAdminPermission(provider.getSpaceId());
        ActionCommand command = toActionCommand(action.getProviderService(), dto);
        command.setActionKey(action.getActionKey());
        return ReqResult.success(ConnectorDtoConverter.toActionDto(catalogService.saveAction(command)));
    }

    @DeleteMapping("/actions/{id}")
    @Operation(summary = "删除动作")
    public ReqResult<Void> deleteAction(@PathVariable Long id) {
        ConnectorAction action = catalogService.requireAction(id);
        ConnectorProvider provider = requireSpaceManagedProvider(action.getProviderService());
        spacePermissionService.checkSpaceAdminPermission(provider.getSpaceId());
        catalogService.deleteAction(id);
        return ReqResult.success();
    }

    @PostMapping("/actions/{id}/status")
    @Operation(summary = "启停用动作（仅空间自建动作；官方目录动作只读）")
    public ReqResult<Void> setActionStatus(@PathVariable Long id,
                                           @RequestParam(defaultValue = "true") boolean enabled) {
        ConnectorAction action = catalogService.requireAction(id);
        ConnectorProvider provider = requireSpaceManagedProvider(action.getProviderService());
        spacePermissionService.checkSpaceAdminPermission(provider.getSpaceId());
        catalogService.setActionStatus(id, enabled);
        return ReqResult.success();
    }

    // ---------- 导入导出 ----------
    // 官方目录供给（导入落 space_id=0）：连接器与 MCP 模块无关，不复用 MCP 权限资源码；
    // 连接器自己的权限资源码体系尚未建立，官方导入门禁暂不挂（待权限设计补齐后再挂）。
    // 空间侧导入（落本空间自定义目录 managed_by=user）走空间管理员权限；
    // 导出仅元数据（不含凭证），与目录详情同可见性：空间成员即可。

    @PostMapping("/import")
    @Operation(summary = "官方目录导入 dry-run（open-connector catalog/OpenAPI 转换包；返回 diff 报告不落库）")
    public ReqResult<ImportReportDto> importDryRun(@RequestBody JSONObject bundle) {
        return ReqResult.success(importService.dryRun(RequestContext.get().getUserId(), bundle));
    }

    @PostMapping("/import/apply")
    @Operation(summary = "确认执行最近一次官方目录 dry-run 的 diff（单事务批量落库）")
    public ReqResult<ImportReportDto> importApply(@Valid @RequestBody ImportApplyDto dto) {
        return ReqResult.success(importService.apply(RequestContext.get().getUserId(), dto.getImportId()));
    }

    @PostMapping("/import/space")
    @Operation(summary = "空间导入 dry-run（落入本空间自定义目录；service 已存在于官方/其他空间则跳过）")
    public ReqResult<ImportReportDto> importSpaceDryRun(@RequestParam Long spaceId,
                                                        @RequestBody JSONObject bundle) {
        spacePermissionService.checkSpaceAdminPermission(spaceId);
        return ReqResult.success(importService.dryRunToSpace(
                RequestContext.get().getUserId(), RequestContext.get().getTenantId(), spaceId, bundle));
    }

    @PostMapping("/import/space/apply")
    @Operation(summary = "确认执行最近一次空间导入 dry-run 的 diff（单事务批量落库）")
    public ReqResult<ImportReportDto> importSpaceApply(@RequestParam Long spaceId,
                                                       @Valid @RequestBody ImportApplyDto dto) {
        spacePermissionService.checkSpaceAdminPermission(spaceId);
        return ReqResult.success(importService.applyToSpace(
                RequestContext.get().getUserId(), spaceId, dto.getImportId()));
    }

    @PostMapping("/export")
    @Operation(summary = "导出（仅元数据，不含凭证）")
    public ReqResult<JSONObject> export(@RequestParam String service, @RequestParam Long spaceId) {
        spacePermissionService.checkSpaceUserPermission(spaceId);
        ConnectorProvider provider = catalogService.requireProvider(service);
        Long providerSpaceId = provider.getSpaceId();
        if (providerSpaceId != null && providerSpaceId != 0L && !providerSpaceId.equals(spaceId)) {
            throw new ConnectorException(ConnectorErrorCode.PROVIDER_NOT_FOUND, "连接器不存在: " + service);
        }
        return ReqResult.success(importService.export(service));
    }

    // ---------- 内部 ----------

    /**
     * 用户侧写操作仅允许空间自建条目；官方目录（space_id=0）走导入管线/管理侧。
     */
    private ConnectorProvider requireSpaceManagedProvider(String service) {
        ConnectorProvider provider = catalogService.requireProvider(service);
        if (provider.getSpaceId() == null || provider.getSpaceId() == 0L) {
            throw new ConnectorException(ConnectorErrorCode.PROTECTED_ZONE,
                    "官方目录条目请通过导入管线或管理侧接口维护: " + service);
        }
        return provider;
    }

    /**
     * 当前用户的连接视图，一次 {@code listByUser} 查询同时产出三样东西，不额外打库。
     * <p>
     * provider 粒度按「任一连接满足即算」聚合：一个 service 可能有多条连接（同一 service 可建多条），
     * 只要有任意一条落在集合里，该 provider 就算已连接 / 已启用。
     *
     * @param connectedServices 建过连接的 service（有行即算——停用的连接仍在里面）
     * @param enabledServices   启用了的 service（连接行 {@code enabled=1}，不看凭证健康度）
     * @param byService         service → 该 service 下的连接明细，用于把连接 id 一起发给前端
     */
    private record MyConnections(Set<String> connectedServices, Set<String> enabledServices,
                                 Map<String, List<ConnectorConnection>> byService) {
    }

    private MyConnections connectionsOfUser(Long tenantId, Long userId) {
        List<ConnectorConnection> connections = connectionRepository.listByUser(tenantId, userId, null);
        Set<String> connected = new HashSet<>();
        Set<String> enabled = new HashSet<>();
        Map<String, List<ConnectorConnection>> byService = new HashMap<>();
        for (ConnectorConnection connection : connections) {
            String service = connection.getProviderService();
            connected.add(service);
            if (Boolean.TRUE.equals(connection.getEnabled())) {
                enabled.add(service);
            }
            byService.computeIfAbsent(service, key -> new ArrayList<>()).add(connection);
        }
        return new MyConnections(connected, enabled, byService);
    }

    /**
     * 给 provider 视图打上「我连了没」「我启用了没」两个标注（都恒有值，便于调用方直接判断），
     * 并把连接 id 一起带上。
     * <p>
     * 带上连接 id 是因为连接级接口（启停/断开/验活）要的是连接 id 而非连接器 id：只给
     * {@code connected=true} 的话，调用方手里只有 provider 的 {@code id}，很容易顺手拿它去调
     * {@code /connections/{id}/status}，得到的是「连接不存在」——错误码本身没错，但没人能从这个
     * 报错里看出「你给的是连接器 id」。把 id 直接发出去，是让正确用法比错误用法更省事。
     */
    private void fillConnectionFlags(ProviderDto dto, String service, MyConnections mine) {
        dto.setConnected(mine.connectedServices().contains(service));
        dto.setConnectionEnabled(mine.enabledServices().contains(service));
        List<ConnectorConnection> connections = mine.byService().get(service);
        if (connections == null || connections.isEmpty()) {
            return;
        }
        dto.setConnections(connections.stream()
                .sorted(CONNECTION_PREFERENCE)
                .map(ConnectorDtoConverter::toConnectionBrief)
                .collect(Collectors.toList()));
        dto.setConnectionId(dto.getConnections().get(0).getId());
    }

    /**
     * 多条连接时挑选「代表连接」的优先级：可执行的排前面，再按 id 升序。
     * <p>
     * 前两级 {@code enabled=1} → {@code status='active'} 与执行时的连接解析
     * （{@code ConnectorConnectionRepository#firstExecutable}）完全一致，最后一级 id 升序也照抄它，
     * 所以这里选出来的就是「引擎真会用的那一条」——发出去的 id 与实际生效的连接不会是两个东西。
     */
    private static final Comparator<ConnectorConnection> CONNECTION_PREFERENCE = Comparator
            .comparingInt((ConnectorConnection c) -> Boolean.TRUE.equals(c.getEnabled()) ? 0 : 1)
            .thenComparingInt(c -> ConnectionStatusEnum.ACTIVE.getCode().equals(c.getStatus()) ? 0 : 1)
            .thenComparing(ConnectorConnection::getId);

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

    private ActionCommand toActionCommand(String service, ActionSaveDto dto) {
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
        command.setSystemLevel(false);
        return command;
    }
}
