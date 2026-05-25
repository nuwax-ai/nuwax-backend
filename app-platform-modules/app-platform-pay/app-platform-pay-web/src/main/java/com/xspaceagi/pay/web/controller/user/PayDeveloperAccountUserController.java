package com.xspaceagi.pay.web.controller.user;

import com.xspaceagi.pay.application.service.PayDeveloperAccountUserApplicationService;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountResponse;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountUserSaveRequest;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/pay/developer-account")
@Tag(name = "支付-开发者账户（用户端）")
public class PayDeveloperAccountUserController {

    @Resource
    private PayDeveloperAccountUserApplicationService payDeveloperAccountUserAppService;

    @PostMapping("/save")
    @Operation(summary = "保存我的开发者账户", description = "租户与用户取自登录态，不传 userId")
    public ReqResult<PayDeveloperAccountResponse> save(@RequestBody PayDeveloperAccountUserSaveRequest request) {
        Assert.notNull(request, "request must not be null");
        return ReqResult.success(payDeveloperAccountUserAppService.save(request));
    }

    @PostMapping("/current")
    @Operation(summary = "查询我的开发者账户", description = "当前租户 + 当前登录用户；无记录时 data 为 null")
    public ReqResult<PayDeveloperAccountResponse> getCurrent() {
        return ReqResult.success(payDeveloperAccountUserAppService.getCurrent());
    }
}
