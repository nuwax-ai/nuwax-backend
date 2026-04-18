package com.xspaceagi.compose.ui.controller;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.Objects;

import com.xspaceagi.system.domain.log.LogPrint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.xspaceagi.compose.application.service.CustomTableDefinitionApplicationService;
import com.xspaceagi.compose.domain.dto.CustomAddBusinessRowDataVo;
import com.xspaceagi.compose.domain.dto.CustomDeleteBusinessRowDataVo;
import com.xspaceagi.compose.domain.dto.CustomUpdateBusinessRowDataVo;
import com.xspaceagi.compose.domain.model.CustomDorisDataRequest;
import com.xspaceagi.compose.sdk.DorisDataPage;
import com.xspaceagi.compose.spec.utils.ComposeExceptionUtils;
import com.xspaceagi.compose.ui.base.BaseController;
import com.xspaceagi.compose.ui.util.ComposeControllerUtil;
import com.xspaceagi.system.sdk.operate.ActionType;
import com.xspaceagi.system.sdk.operate.OperationLogReporter;
import com.xspaceagi.system.sdk.operate.SystemEnum;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.ComposeException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Tag(name = "数据表组件-业务数据管理")
@RestController
@RequestMapping("/api/compose/db/table")
public class ComposeDbBusinessDataController extends BaseController {

    @Resource
    private CustomTableDefinitionApplicationService customTableDefinitionApplicationService;

    @OperationLogReporter(actionType = ActionType.QUERY, action = "查询数据表业务数据", objectName = "业务数据操作", systemCode = SystemEnum.DB_TABLE)
    @LogPrint(step = "业务数据操作-查询数据表业务数据")
    @Operation(summary = "查询数据表业务数据")
    @RequestMapping(path = "/getTableDataById", method = RequestMethod.GET)
    public ReqResult<DorisDataPage<Map<String, Object>>> getTableDataById(CustomDorisDataRequest request) {
        DorisDataPage<Map<String, Object>> data = customTableDefinitionApplicationService
                .queryPageDorisTableDataForWeb(request);
        return ReqResult.success(data);
    }

    @OperationLogReporter(actionType = ActionType.ADD, action = "新增业务数据", objectName = "业务数据操作", systemCode = SystemEnum.DB_TABLE, spelExpression = "#request")
    @LogPrint(step = "业务数据操作-新增业务数据")
    @Operation(summary = "新增业务数据")
    @RequestMapping(path = "/addBusinessData", method = RequestMethod.POST)
    public ReqResult<Map<String, Object>> addBusinessData(@RequestBody CustomAddBusinessRowDataVo request) {
        var userContext = this.getUser();

        // 需要根据 DefaultTableFieldEnum 里面的关键系统字段,进行替换,具体字段:UID,USER_NAME,NICK_NAME
        // 另外需要去掉数据中的主键id字段: ID; 业务行数据id是通过请求参数中的:rowId
        ComposeControllerUtil.enrichRowDataWithUserInfo(request.getRowData(), userContext);

        customTableDefinitionApplicationService.addBusinessData(request, userContext);
        return ReqResult.success();
    }

    @OperationLogReporter(actionType = ActionType.MODIFY, action = "修改业务数据", objectName = "业务数据操作", systemCode = SystemEnum.DB_TABLE, spelExpression = "#request")
    @LogPrint(step = "业务数据操作-修改业务数据")
    @Operation(summary = "修改业务数据")
    @RequestMapping(path = "/updateBusinessData", method = RequestMethod.POST)
    public ReqResult<Map<String, Object>> updateBusinessData(@RequestBody CustomUpdateBusinessRowDataVo request) {
        var userContext = this.getUser();

        // 需要根据 DefaultTableFieldEnum 里面的关键系统字段,进行替换,具体字段:UID,USER_NAME,NICK_NAME
        // 另外需要去掉数据中的主键id字段: ID; 业务行数据id是通过请求参数中的:rowId
        ComposeControllerUtil.enrichRowDataWithUserInfo(request.getRowData(), userContext);

        customTableDefinitionApplicationService.updateBusinessData(request, userContext);
        return ReqResult.success();
    }

    @LogPrint(step = "业务数据操作-删除业务数据")
    @Operation(summary = "删除业务数据")
    @RequestMapping(path = "/deleteBusinessData", method = RequestMethod.POST)
    public ReqResult<Void> deleteBusinessData(@RequestBody CustomDeleteBusinessRowDataVo request) {
        var userContext = this.getUser();
        
        // 1. 查询完整的行数据（用于日志记录，便于后续恢复）
        Map<String, Object> rowData = customTableDefinitionApplicationService
                .queryRowDataByTableIdAndRowId(request.getTableId(), request.getRowId());
        
        // 2. 调用 Service 层删除（带上 @OperationLogReporter 注解）
        customTableDefinitionApplicationService.deleteBusinessData(request, rowData, userContext);
        return ReqResult.success();
    }

    @OperationLogReporter(actionType = ActionType.QUERY, action = "导出业务表数据为Excel", objectName = "业务数据操作", systemCode = SystemEnum.DB_TABLE)
    @LogPrint(step = "业务数据操作-导出业务表数据为Excel")
    @Operation(summary = "导出业务表数据为Excel")
    @GetMapping("/exportExcel/{tableId}")
    public void exportTableDataExcel(@PathVariable Long tableId, HttpServletResponse response) throws IOException {
        var userContext = this.getUser();

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = "exported_data_" + tableId + ".xlsx";
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");

        OutputStream outputStream = response.getOutputStream();

        customTableDefinitionApplicationService.exportTableDataToExcel(tableId, outputStream, userContext);

    }

    @OperationLogReporter(actionType = ActionType.DELETE, action = "清空业务数据", objectName = "业务数据操作", systemCode = SystemEnum.DB_TABLE)
    @LogPrint(step = "业务数据操作-清空业务数据")
    @Operation(summary = "清空业务数据")
    @RequestMapping(path = "/clearBusinessData/{tableId}", method = RequestMethod.GET)
    public ReqResult<Void> clearBusinessData(@PathVariable Long tableId) {
        var userContext = this.getUser();
        // check tableId 不为空
        if (Objects.isNull(tableId)) {
            throw ComposeException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "表Id");
        }
        customTableDefinitionApplicationService.clearBusinessData(tableId, userContext);
        return ReqResult.success();
    }

    @OperationLogReporter(actionType = ActionType.ADD, action = "导入业务表数据Excel", objectName = "业务数据操作", systemCode = SystemEnum.DB_TABLE)
    @LogPrint(step = "业务数据操作-导入业务表数据Excel")
    @Operation(summary = "导入业务表数据Excel")
    @PostMapping("/importExcel/{tableId}")
    public ReqResult<Void> importTableDataExcel(@PathVariable Long tableId, @RequestParam("file") MultipartFile file) {
        var userContext = this.getUser();
        try {
            customTableDefinitionApplicationService.importTableDataFromExcel(tableId, file.getInputStream(),
                    userContext);
            return ReqResult.success();
        } catch (Exception e) {
            log.error("Import Excel failed", e);
            // 使用用户友好的错误信息，提供更清晰的错误提示
            String friendlyMessage = ComposeExceptionUtils.getUserFriendlyErrorMessage(e);
            return ReqResult.error("导入Excel数据失败: " + friendlyMessage);
        }
    }

    @OperationLogReporter(actionType = ActionType.ADD, action = "恢复被删除的业务数据", objectName = "业务数据操作", systemCode = SystemEnum.DB_TABLE, spelExpression = "#extraContent")
    @LogPrint(step = "业务数据操作-恢复被删除的业务数据")
    @Operation(summary = "恢复被删除的业务数据")
    @PostMapping(value = "/restoreBusinessData", consumes = "application/x-www-form-urlencoded")
    public ReqResult<String> restoreBusinessData(
            @RequestParam("extraContent") String extraContent,
            @RequestParam(value = "forceRestore", defaultValue = "false") Boolean forceRestore) {
        var userContext = this.getUser();
        try {
            String result = customTableDefinitionApplicationService.restoreBusinessDataByExtraContent(
                    extraContent,
                    forceRestore,
                    userContext
            );
            return ReqResult.success(result);
        } catch (Exception e) {
            log.error("Failed to restore data", e);
            return ReqResult.error("恢复数据失败: " + e.getMessage());
        }
    }

}