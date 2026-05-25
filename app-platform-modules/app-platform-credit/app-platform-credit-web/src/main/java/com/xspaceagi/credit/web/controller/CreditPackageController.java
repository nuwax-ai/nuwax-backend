package com.xspaceagi.credit.web.controller;

import com.xspaceagi.credit.app.service.CreditPackageService;
import com.xspaceagi.credit.sdk.dto.CreditPackageDTO;
import com.xspaceagi.credit.web.controller.dto.SortUpdateDTO;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Slf4j
@RestController
@RequestMapping("/api/system/credit/package")
@Tag(name = "积分套餐管理（管理端）")
public class CreditPackageController {

    @Resource
    private CreditPackageService creditPackageService;

    @RequireResource(SUBSCRIPTION_POINTS_MODIFY)
    @PostMapping("/create")
    @Operation(summary = "创建积分套餐")
    public ReqResult<Long> createPackage(@RequestBody CreditPackageDTO dto) {
        return ReqResult.success(creditPackageService.createPackage(dto));
    }

    @RequireResource(SUBSCRIPTION_POINTS_MODIFY)
    @PostMapping("/update")
    @Operation(summary = "更新积分套餐")
    public ReqResult<Boolean> updatePackage(@RequestBody CreditPackageDTO dto) {
        return ReqResult.success(creditPackageService.updatePackage(dto));
    }

    @RequireResource(SUBSCRIPTION_POINTS_MODIFY)
    @PostMapping("/sort/update")
    @Operation(summary = "更新积分套餐排序")
    public ReqResult<Void> updatePackageSort(@RequestBody List<SortUpdateDTO> updateDTOS) {
        updateDTOS.forEach(updateDTO -> {
            CreditPackageDTO dto = new CreditPackageDTO();
            dto.setId(updateDTO.getId());
            dto.setSort(updateDTO.getSort());
            creditPackageService.updatePackage(dto);
        });
        return ReqResult.success();
    }

    @RequireResource(SUBSCRIPTION_POINTS_DELETE)
    @PostMapping("/{id}/delete")
    @Operation(summary = "删除积分套餐")
    public ReqResult<Boolean> deletePackage(@PathVariable Long id) {
        return ReqResult.success(creditPackageService.deletePackage(id));
    }

    @RequireResource(SUBSCRIPTION_POINTS_QUERY)
    @GetMapping("/list")
    @Operation(summary = "查询积分套餐列表")
    public ReqResult<List<CreditPackageDTO>> getPackageList(
            @RequestParam(required = false) Integer status) {
        return ReqResult.success(creditPackageService.getPackageList(status));
    }
}
