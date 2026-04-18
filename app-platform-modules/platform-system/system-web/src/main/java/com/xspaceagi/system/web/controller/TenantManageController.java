package com.xspaceagi.system.web.controller;

import com.xspaceagi.system.application.dto.TenantAddDto;
import com.xspaceagi.system.application.dto.TenantDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.infra.dao.entity.Tenant;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "系统管理-租户管理相关接口")
@RestController
@RequestMapping("/api/v1/tenant")
public class TenantManageController {

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;


    @Operation(summary = "查询租户列表")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<List<TenantDto>> listQuery() {
        checkPermission();
        return ReqResult.success(tenantConfigApplicationService.getTenantList());
    }

    @Operation(summary = "新增租户")
    @RequestMapping(path = "/add", method = RequestMethod.POST)
    public ReqResult<Void> add(@RequestBody @Valid TenantAddDto tenant) {
        checkPermission();
        if (tenantConfigApplicationService.queryTenantIdByDomainName(tenant.getDomain()) != null) {
            return ReqResult.error("域名`" + tenant.getDomain() + "`已被占用");
        }
        tenant.setDomain(tenant.getDomain().toLowerCase());
        tenantConfigApplicationService.addTenant(tenant);
        return ReqResult.success();
    }

    @Operation(summary = "更新租户信息")
    @RequestMapping(path = "/update", method = RequestMethod.POST)
    public ReqResult<Void> update(@RequestBody Tenant tenant) {
        checkPermission();
        tenantConfigApplicationService.updateTenant(tenant);
        return ReqResult.success();
    }

    private void checkPermission() {
        //租户ID为1的管理员拥有所有租户的管理权限
        if (RequestContext.get().getTenantId() != 1) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }
    }
}