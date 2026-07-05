package com.xspaceagi.compose.ui.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.compose.application.service.CustomTableDefinitionApplicationService;
import com.xspaceagi.compose.domain.model.CustomTableDefinitionModel;
import com.xspaceagi.compose.domain.repository.ICustomTableDefinitionRepository;
import com.xspaceagi.compose.ui.base.BaseController;
import com.xspaceagi.compose.ui.dto.*;
import com.xspaceagi.sandbox.SandboxUtils;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.domain.log.LogPrint;
import com.xspaceagi.system.infra.service.QueryVoListDelegateService;
import com.xspaceagi.system.sdk.operate.ActionType;
import com.xspaceagi.system.sdk.operate.OperationLogReporter;
import com.xspaceagi.system.sdk.operate.SystemEnum;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ResourceEnum;
import com.xspaceagi.system.spec.page.PageQueryParamVo;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

@Tag(name = "数据表组件-表结构配置")
@RestController
@RequestMapping("/api/compose/db/table")
public class ComposeDbTableConfigController extends BaseController {

    @Resource
    private CustomTableDefinitionApplicationService customTableDefinitionApplicationService;

    @Resource
    private ICustomTableDefinitionRepository customTableDefinitionRepository;

    @Resource
    private QueryVoListDelegateService queryVoListDelegateService;

    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @RequireResource(ResourceEnum.COMPONENT_LIB_CREATE)
    @OperationLogReporter(actionType = ActionType.ADD, action = "新增表定义", objectName = "表定义配置", systemCode = SystemEnum.DB_TABLE)
    @LogPrint(step = "表定义配置-新增表定义")
    @Operation(summary = "新增表定义")
    @PostMapping("/add")
    public ReqResult<Long> addTableDefinition(@RequestBody CustomTableAddRequest addRequest, HttpServletRequest request) {
        if (SandboxUtils.isSandboxRequest(request)) {
            if (addRequest.getSpaceId() == null) {
                Long personalSpaceId = spaceApplicationService.getPersonalSpaceId(RequestContext.get().getUserId());
                addRequest.setSpaceId(personalSpaceId);
            }
        }
        UserContext userContext = this.getUser();
        UserDataPermissionDto userDataPermission = userDataPermissionRpcService.getUserDataPermission(userContext.getUserId());
        Long ct = customTableDefinitionApplicationService.countUserTotalTable(userContext.getUserId());
        userDataPermission.checkMaxDataTableCount(ct.intValue());
        var model = CustomTableAddRequest.convert2Model(addRequest);

        var data = customTableDefinitionApplicationService
                .addInfo(model, userContext);
        return ReqResult.success(data);
    }

    @RequireResource(ResourceEnum.COMPONENT_LIB_MODIFY)
    @OperationLogReporter(actionType = ActionType.MODIFY, action = "更新表定义", objectName = "表定义配置", systemCode = SystemEnum.DB_TABLE)
    @LogPrint(step = "表定义配置-更新表名称和描述信息")
    @Operation(summary = "更新表名称和描述信息")
    @PostMapping("/updateTableName")
    public ReqResult<Void> updateTableName(@RequestBody CustomTableUpdateNameRequest request) {
        UserContext userContext = this.getUser();

        var model = CustomTableUpdateNameRequest.convert2Model(request);

        customTableDefinitionApplicationService.updateTableName(model, userContext);
        return ReqResult.success();
    }

    @RequireResource(ResourceEnum.COMPONENT_LIB_MODIFY)
    @OperationLogReporter(actionType = ActionType.MODIFY, action = "更新表定义", objectName = "表定义配置", systemCode = SystemEnum.DB_TABLE)
    @LogPrint(step = "表定义配置-更新表定义")
    @Operation(summary = "更新表定义")
    @PostMapping("/updateTableDefinition")
    public ReqResult<Void> updateTableDefinition(@RequestBody CustomTableUpdateDefineRequest request) {
        UserContext userContext = this.getUser();

        var fieldList = CustomTableUpdateDefineRequest.convert2Model(request);

        var tableId = request.getId();

        customTableDefinitionApplicationService.updateInfo(tableId, fieldList, userContext);
        return ReqResult.success();
    }

    @RequireResource(ResourceEnum.COMPONENT_LIB_DELETE)
    @OperationLogReporter(actionType = ActionType.DELETE, action = "删除表定义", objectName = "表定义配置", systemCode = SystemEnum.DB_TABLE)
    @LogPrint(step = "表定义配置-删除表定义")
    @Operation(summary = "删除表定义")
    @PostMapping("/delete/{id}")
    public ReqResult<Void> deleteTableDefinition(@PathVariable("id") Long id) {
        UserContext userContext = this.getUser();
        customTableDefinitionApplicationService.deleteById(id, userContext);
        return ReqResult.success();
    }

    @RequireResource(ResourceEnum.COMPONENT_LIB_QUERY_LIST)
    @OperationLogReporter(actionType = ActionType.QUERY, action = "查询表定义列表", objectName = "表定义配置", systemCode = SystemEnum.DB_TABLE)
    @LogPrint(step = "表定义配置-查询表定义列表")
    @Operation(summary = "查询表定义列表")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<IPage<CustomTableDefinitionModel>> list(
            @RequestBody PageQueryVo<CustomTableQueryRequest> pageQueryVo, HttpServletRequest request) {
        if (SandboxUtils.isSandboxRequest(request)) {
            if (pageQueryVo.getQueryFilter() == null) {
                pageQueryVo.setQueryFilter(new CustomTableQueryRequest());
            }
            if (pageQueryVo.getQueryFilter().getSpaceId() == null) {
                Long personalSpaceId = spaceApplicationService.getPersonalSpaceId(RequestContext.get().getUserId());
                pageQueryVo.getQueryFilter().setSpaceId(personalSpaceId);
            }
        }
        Assert.notNull(pageQueryVo, "pageQueryVo is null");
        Assert.notNull(pageQueryVo.getQueryFilter(), "queryFilter is null");
        Assert.notNull(pageQueryVo.getQueryFilter().getSpaceId(), "spaceId is null");
        spacePermissionService.checkSpaceUserPermission(pageQueryVo.getQueryFilter().getSpaceId());
        var filter = pageQueryVo.getQueryFilter();
        pageQueryVo.setQueryFilter(filter);

        PageQueryParamVo pageQueryParamVo = new PageQueryParamVo(pageQueryVo);

        SuperPage<CustomTableDefinitionModel> superPage = this.queryVoListDelegateService.queryVoList(
                this.customTableDefinitionRepository,
                pageQueryParamVo, null);
        superPage.getRecords().forEach(item -> item.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(item.getIcon(), item.getTableName(), "table")));
        return ReqResult.success(superPage);
    }

    @RequireResource(ResourceEnum.COMPONENT_LIB_QUERY_DETAIL)
    @OperationLogReporter(actionType = ActionType.QUERY, action = "查询表定义详情", objectName = "表定义配置", systemCode = SystemEnum.DB_TABLE)
    @LogPrint(step = "表定义配置-查询表定义详情")
    @Operation(summary = "查询表定义详情")
    @RequestMapping(path = "/detailById", method = RequestMethod.GET)
    public ReqResult<FrontTableDefineResponseVo> getTableDefinitionById(@RequestParam("id") Long id) {
        var tableModel = customTableDefinitionApplicationService.queryOneTableDefineVo(id);
        var tableDefineVo = FrontTableDefineResponseVo.convert2FrontTableDefineResponseVo(tableModel);

        // 查询业务数据,是否存在数据了
        var existTableDataFlag = customTableDefinitionApplicationService.existTableData(id);
        tableDefineVo.setExistTableDataFlag(existTableDataFlag);
        tableDefineVo.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(tableDefineVo.getIcon(), tableDefineVo.getTableName(), "table"));
        return ReqResult.success(tableDefineVo);
    }

    @RequireResource(ResourceEnum.COMPONENT_LIB_QUERY_DETAIL)
    @OperationLogReporter(actionType = ActionType.QUERY, action = "查询表是否存在业务数据", objectName = "表定义配置", systemCode = SystemEnum.DB_TABLE)
    @LogPrint(step = "表定义配置-查询表是否存在业务数据")
    @Operation(summary = "查询表是否存在业务数据")
    @RequestMapping(path = "/existTableData", method = RequestMethod.GET)
    public ReqResult<Boolean> existTableData(@RequestParam("tableId") Long tableId) {
        Boolean existTableDataFlag = customTableDefinitionApplicationService.existTableData(tableId);
        return ReqResult.success(existTableDataFlag);
    }

    @RequireResource(ResourceEnum.COMPONENT_LIB_COPY)
    @OperationLogReporter(actionType = ActionType.ADD, action = "复制表结构定义", objectName = "表定义配置", systemCode = SystemEnum.DB_TABLE)
    @LogPrint(step = "表定义配置-复制表结构定义")
    @Operation(summary = "复制表结构定义")
    @RequestMapping(path = "/copyTableDefinition", method = RequestMethod.POST)
    public ReqResult<Long> copyTableDefinition(@RequestParam("tableId") Long tableId) {
        UserDataPermissionDto userDataPermission = userDataPermissionRpcService.getUserDataPermission(RequestContext.get().getUserId());
        userDataPermission.checkMaxDataTableCount(customTableDefinitionApplicationService.countUserTotalTable(userDataPermission.getUserId()).intValue());
        UserContext userContext = this.getUser();
        Long newTableId = customTableDefinitionApplicationService.copyTableDefinition(tableId, userContext);
        return ReqResult.success(newTableId);
    }

}