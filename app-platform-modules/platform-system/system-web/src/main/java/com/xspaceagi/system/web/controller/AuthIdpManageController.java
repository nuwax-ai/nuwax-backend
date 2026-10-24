package com.xspaceagi.system.web.controller;

import com.xspaceagi.system.application.dto.AuthIdpAutoRedirectDto;
import com.xspaceagi.system.application.dto.AuthIdpSaveDto;
import com.xspaceagi.system.application.dto.AuthIdpStatusDto;
import com.xspaceagi.system.application.dto.AuthIdpVo;
import com.xspaceagi.system.application.service.AuthIdpConfigApplicationService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.web.controller.base.BaseController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Slf4j
@Tag(name = "登录方式管理", description = "CAS / OAuth2 / 微信登录方式配置管理")
@RestController
@RequestMapping("/api/system/idp")
public class AuthIdpManageController extends BaseController {

    @Resource
    private AuthIdpConfigApplicationService authIdpConfigApplicationService;

    @RequireResource(AUTH_METHOD_ADD)
    @Operation(summary = "新增登录方式（默认停用）")
    @PostMapping("/create")
    public ReqResult<AuthIdpVo> create(@RequestBody AuthIdpSaveDto dto) {
        return authIdpConfigApplicationService.create(dto, getUser());
    }

    @RequireResource(AUTH_METHOD_MODIFY)
    @Operation(summary = "编辑登录方式（类型不可变更；secret 留空不改）")
    @PostMapping("/update")
    public ReqResult<AuthIdpVo> update(@RequestBody AuthIdpSaveDto dto) {
        return authIdpConfigApplicationService.update(dto, getUser());
    }

    @RequireResource(AUTH_METHOD_DELETE)
    @Operation(summary = "删除登录方式（仍有用户绑定时拒绝）")
    @PostMapping("/delete/{id}")
    public ReqResult<Void> delete(@Parameter(description = "配置ID", required = true) @PathVariable("id") Long id) {
        return authIdpConfigApplicationService.delete(id, getUser());
    }

    @RequireResource(AUTH_METHOD_QUERY)
    @Operation(summary = "登录方式列表（config 脱敏）")
    @GetMapping("/list")
    public ReqResult<List<AuthIdpVo>> list() {
        return authIdpConfigApplicationService.list(getUser());
    }

    @RequireResource(AUTH_METHOD_ENABLE)
    @Operation(summary = "启用/停用（停用联动取消未登录自动跳转）")
    @PostMapping("/updateStatus")
    public ReqResult<Void> updateStatus(@RequestBody AuthIdpStatusDto dto) {
        return authIdpConfigApplicationService.updateStatus(dto.getId(), dto.getEnabled(), getUser());
    }

    @RequireResource(AUTH_METHOD_ENABLE)
    @Operation(summary = "设置/取消未登录自动跳转（id 为空表示取消，租户内全局唯一）")
    @PostMapping("/auto-redirect")
    public ReqResult<Void> setAutoRedirect(@RequestBody(required = false) AuthIdpAutoRedirectDto dto) {
        return authIdpConfigApplicationService.setAutoRedirect(dto == null ? null : dto.getId(), getUser());
    }
}
