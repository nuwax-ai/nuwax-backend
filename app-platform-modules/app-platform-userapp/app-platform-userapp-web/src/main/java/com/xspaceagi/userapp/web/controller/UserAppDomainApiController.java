package com.xspaceagi.userapp.web.controller;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.userapp.application.service.IUserAppDomainApplicationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

@Tag(name = "User app", description = "List domains bound to user apps")
@RestController
@Slf4j
public class UserAppDomainApiController {

    @Resource
    private IUserAppDomainApplicationService userAppDomainApplicationService;

    @Operation(summary = "Domain list")
    @GetMapping(value = "/api/v1/tenant/userapp/domains", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<List<String>> list() {
        return ReqResult.success(TenantFunctions.callWithIgnoreCheck(
                () -> userAppDomainApplicationService.listAllDomains()));
    }
}
