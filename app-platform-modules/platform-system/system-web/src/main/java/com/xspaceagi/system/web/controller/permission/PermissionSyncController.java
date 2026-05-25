package com.xspaceagi.system.web.controller.permission;

import com.xspaceagi.system.application.dto.permission.export.PermissionExportDto;
import com.xspaceagi.system.application.service.PermissionDiffService;
import com.xspaceagi.system.application.service.PermissionExportService;
import com.xspaceagi.system.application.service.PermissionImportService;
import com.xspaceagi.system.infra.dao.entity.Tenant;
import com.xspaceagi.system.infra.dao.service.TenantService;
import com.xspaceagi.system.spec.annotation.SaasAdmin;
import com.xspaceagi.system.spec.constants.PermissionSyncConstants;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/system/permission")
public class PermissionSyncController {

    @Resource
    private PermissionExportService permissionExportService;

    @Resource
    private PermissionImportService permissionImportService;

    @Resource
    private PermissionDiffService permissionDiffService;

    @Resource
    private TenantService tenantService;

    @SaasAdmin
    @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Object> export(@RequestParam String version) {
        try {
            PermissionExportDto dto = permissionExportService.exportConfig(version);
            String json = JsonSerializeUtil.toJSONString(dto);

            String saveToPath = "./" + PermissionSyncConstants.PERMISSION_JSON_EXPORT_BASE_PATH + "/" + PermissionSyncConstants.buildExportFileName(version);
            Path path = Paths.get(saveToPath).toAbsolutePath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, json, StandardCharsets.UTF_8);
            return ReqResult.success("已导出");
        } catch (IOException e) {
            return ReqResult.error(e.getMessage());
        }
    }

    @SaasAdmin
    @GetMapping(value = "/import", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<String> importPermission(@RequestParam Long tenantId, @RequestParam String version) {
        Tenant tenant = tenantService.getById(tenantId);
        if (tenant == null) {
            return ReqResult.error("租户不存在");
        }
        permissionImportService.importToTenant(tenant, version);
        return ReqResult.success("导入成功");
    }

    @SaasAdmin
    @GetMapping(value = "/diff", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<String> diff(@RequestParam String fromVersion, @RequestParam String toVersion) {
        try {
            var diff = permissionDiffService.generateDiff(fromVersion, toVersion);
            String json = JsonSerializeUtil.toJSONString(diff);

            String fileName = PermissionSyncConstants.buildDiffFileName(toVersion);
            String saveToPath = "./" + PermissionSyncConstants.PERMISSION_JSON_EXPORT_BASE_PATH + "/" + fileName;
            Path path = Paths.get(saveToPath).toAbsolutePath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, json, StandardCharsets.UTF_8);
            return ReqResult.success("已生成差异文件: " + fileName);
        } catch (IOException e) {
            return ReqResult.error(e.getMessage());
        }
    }

    @SaasAdmin
    @GetMapping(value = "/import-diff", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<String> importDiff(@RequestParam Long tenantId, @RequestParam String version) {
        Tenant tenant = tenantService.getById(tenantId);
        if (tenant == null) {
            return ReqResult.error("租户不存在");
        }
        permissionImportService.importDiffToTenant(tenant, version);
        return ReqResult.success("差异导入成功");
    }
}
