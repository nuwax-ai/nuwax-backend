package com.xspaceagi.pay.web.controller.admin;

import com.xspaceagi.pay.application.service.PayDeveloperAccountAdminApplicationService;
import com.xspaceagi.pay.spec.dto.PageResult;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountAdminPageRequest;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountAdminSaveRequest;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountAdminUserQueryRequest;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountByIdRequest;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountResponse;
import com.xspaceagi.system.spec.annotation.RequireResource;
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

import static com.xspaceagi.system.spec.enums.ResourceEnum.PAY_EARNINGS_MODIFY;
import static com.xspaceagi.system.spec.enums.ResourceEnum.PAY_EARNINGS_QUERY;

@Slf4j
@RestController
@RequestMapping("/api/system/pay/developer-account")
@Tag(name = "支付-开发者账户（管理端）")
public class PayDeveloperAccountAdminController {

    @Resource
    private PayDeveloperAccountAdminApplicationService payDeveloperAccountAdminAppService;

    @RequireResource(PAY_EARNINGS_MODIFY)
    @PostMapping("/save")
    @Operation(
            summary = "保存（管理）",
            description = "租户取自请求上下文（域名等），body 仅须传目标 userId；不传 id 时按「当前租户 + userId」upsert。需平台管理员（/api/system/）")
    public ReqResult<PayDeveloperAccountResponse> save(@RequestBody PayDeveloperAccountAdminSaveRequest request) {
        Assert.notNull(request, "request must not be null");
        return ReqResult.success(payDeveloperAccountAdminAppService.save(request));
    }

    @RequireResource(PAY_EARNINGS_QUERY)
    @PostMapping("/get-by-id")
    @Operation(summary = "按主键查询（管理）", description = "仅当前租户数据")
    public ReqResult<PayDeveloperAccountResponse> getById(@RequestBody PayDeveloperAccountByIdRequest request) {
        Assert.notNull(request, "request must not be null");
        Assert.notNull(request.getId(), "id must not be null");
        return ReqResult.success(payDeveloperAccountAdminAppService.getById(request.getId()));
    }

    @RequireResource(PAY_EARNINGS_QUERY)
    @PostMapping("/get-by-user")
    @Operation(summary = "按用户查询（管理）", description = "当前租户下指定 userId；无记录时 data 为 null")
    public ReqResult<PayDeveloperAccountResponse> getByUser(@RequestBody PayDeveloperAccountAdminUserQueryRequest request) {
        Assert.notNull(request, "request must not be null");
        Assert.notNull(request.getUserId(), "userId must not be null");
        return ReqResult.success(payDeveloperAccountAdminAppService.getByUser(request));
    }

    @RequireResource(PAY_EARNINGS_QUERY)
    @PostMapping("/page")
    @Operation(summary = "分页查询（管理）", description = "仅当前租户；可选 userId、创建时间范围；可选 userNameKeyword")
    public ReqResult<PageResult<PayDeveloperAccountResponse>> page(@RequestBody PayDeveloperAccountAdminPageRequest request) {
        Assert.notNull(request, "request must not be null");
        return ReqResult.success(payDeveloperAccountAdminAppService.page(request));
    }

    @RequireResource(PAY_EARNINGS_MODIFY)
    @PostMapping("/delete-by-id")
    @Operation(summary = "按主键删除（管理）", description = "仅可删当前租户下数据")
    public ReqResult<Void> deleteById(@RequestBody PayDeveloperAccountByIdRequest request) {
        Assert.notNull(request, "request must not be null");
        Assert.notNull(request.getId(), "id must not be null");
        payDeveloperAccountAdminAppService.deleteById(request);
        return ReqResult.success(null);
    }
}
