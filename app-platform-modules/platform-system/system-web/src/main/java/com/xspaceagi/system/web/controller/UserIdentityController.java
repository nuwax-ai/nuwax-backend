package com.xspaceagi.system.web.controller;

import com.xspaceagi.system.application.dto.UserIdentityVo;
import com.xspaceagi.system.application.service.AuthIdpApplicationService;
import com.xspaceagi.system.application.service.UserIdentityApplicationService;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.web.controller.base.BaseController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户中心外部身份绑定管理
 */
@Slf4j
@Tag(name = "外部身份绑定", description = "用户中心绑定的第三方账号管理")
@RestController
@RequestMapping("/api/user/identity")
public class UserIdentityController extends BaseController {

    @Resource
    private UserIdentityApplicationService userIdentityApplicationService;

    @Resource
    private AuthIdpApplicationService authIdpApplicationService;

    @Operation(summary = "我绑定的外部身份列表")
    @GetMapping("/list")
    public ReqResult<List<UserIdentityVo>> list() {
        return ReqResult.success(userIdentityApplicationService.listMine());
    }

    @Operation(summary = "发起绑定（302 跳转 IdP，完成后自动绑到当前账号）")
    @GetMapping("/bind/{providerId}")
    public void bind(@Parameter(description = "登录方式配置ID", required = true) @PathVariable("providerId") Long providerId,
            @Parameter(description = "绑定完成后的回跳目标（本站相对路径）") @RequestParam(value = "redirect", required = false) String redirect,
            HttpServletResponse response) throws Exception {
        getUser();
        response.sendRedirect(authIdpApplicationService.buildAuthorizeUrl(providerId, redirect, true));
    }

    @Operation(summary = "解绑（唯一登录方式且未设密码时拒绝）")
    @PostMapping("/unbind/{identityId}")
    public ReqResult<Void> unbind(@Parameter(description = "绑定关系ID（/api/user/identity/list 返回的 id）", required = true) @PathVariable("identityId") Long identityId) {
        getUser();
        userIdentityApplicationService.unbind(identityId);
        return ReqResult.success();
    }
}
