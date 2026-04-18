package com.xspaceagi.system.web.controller;

import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.TenantConfigItemDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.utils.I18nUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "系统管理-系统配置相关接口")
@RestController
@RequestMapping("/api/system/config")
public class TenantConfigManageController {

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @RequireResource({SYSTEM_SETTING_BASIC})
    @Operation(summary = "查询配置列表")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<List<TenantConfigItemDto>> listQuery() {
        List<TenantConfigItemDto> tenantConfigList = tenantConfigApplicationService.getTenantConfigList();
        I18nUtil.replaceSystemMessage(tenantConfigList);
        return ReqResult.success(tenantConfigList);
    }

    @RequireResource({SYSTEM_SETTING_SAVE})
    @Operation(summary = "更新配置信息")
    @RequestMapping(path = "/add", method = RequestMethod.POST)
    public ReqResult<Void> update(@RequestBody TenantConfigDto tenantConfigDto) {
        tenantConfigDto.setTenantId(RequestContext.get().getTenantId());
        tenantConfigApplicationService.updateConfig(tenantConfigDto);
        return ReqResult.success();
    }

    @RequireResource({SYSTEM_THEME_CONFIG_SAVE})
    @Operation(summary = "更新主题配置")
    @RequestMapping(path = "/update-theme", method = RequestMethod.POST)
    public ReqResult<Void> updateTheme(@RequestBody TenantConfigDto tenantConfigDto) {
        tenantConfigDto.setTenantId(RequestContext.get().getTenantId());
        tenantConfigApplicationService.updateConfig(tenantConfigDto);
        return ReqResult.success();
    }
}