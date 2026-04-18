package com.xspaceagi.custompage.ui.web.controller;

import com.xspaceagi.custompage.application.service.ICustomPageDomainApplicationService;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Custom page domains", description = "List domains bound to custom pages")
@RestController
@Slf4j
public class CustomPageDomainApiController extends BaseController {

    @Resource
    private ICustomPageDomainApplicationService customPageDomainApplicationService;

    @Operation(summary = "Domain list")
    @GetMapping(value = "/api/v1/tenant/custom-page/domains", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<List<String>> list() {
        return ReqResult.success(TenantFunctions.callWithIgnoreCheck(() -> customPageDomainApplicationService.listAllDomains()));
    }

}
