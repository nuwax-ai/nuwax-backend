package com.xspaceagi.eco.market.web.controller;

import com.xspaceagi.system.infra.service.QueryVoListDelegateService;
import com.xspaceagi.system.spec.exception.BizException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.xspaceagi.eco.market.domain.model.EcoMarketClientPublishConfigModel;
import com.xspaceagi.eco.market.spec.app.service.IEcoMarketClientPublishConfigApplicationService;
import com.xspaceagi.eco.market.spec.constant.EcoMarketApiConstant;
import com.xspaceagi.eco.market.web.controller.base.BaseController;
import com.xspaceagi.eco.market.domain.dto.req.UpdateAndEnableConfigReqDTO;
import com.xspaceagi.system.sdk.operate.ActionType;
import com.xspaceagi.system.sdk.operate.OperationLogReporter;
import com.xspaceagi.system.sdk.operate.SystemEnum;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Tag(name = "生态市场-客户端-发布配置")
@RestController
@RequestMapping(EcoMarketApiConstant.ClientPublishConfig.BASE)
@Slf4j
public class EcoMarketClientPublishConfigController extends BaseController {

    @Resource
    private QueryVoListDelegateService queryVoListDelegateService;

    @Resource
    private IEcoMarketClientPublishConfigApplicationService ecoMarketClientPublishConfigApplicationService;

    /**
     * 启用配置
     *
     * @param uid 配置唯一标识
     * @return 启用结果
     */
    @Operation(summary = "启用配置", description = "启用生态市场配置")
    @PostMapping("/enable")
    public ReqResult<EcoMarketClientPublishConfigModel> enableConfig(
            @Parameter(description = "配置唯一标识") @RequestParam("uid") String uid) {
        log.info("Enable config: uid={}", uid);

        // 获取当前用户上下文
        var userContext = getUser();

        // 调用应用服务启用配置
        EcoMarketClientPublishConfigModel result = ecoMarketClientPublishConfigApplicationService.enableConfig(uid,
                userContext);

        return ReqResult.success(result);

    }

    /**
     * 更新保存并启用配置
     * <p>
     * 1. 入参: uid , configParamJson 请求参数配置
     * </p>
     *
     * @param reqDTO 请求DTO，包含配置唯一标识和配置参数JSON
     * @return 启用结果
     */
    @OperationLogReporter(actionType = ActionType.MODIFY, action = "更新保存并启用配置", objectName = "生态市场客户端配置", systemCode = SystemEnum.ECO_MARKET)
    @Operation(summary = "更新保存并启用配置", description = "更新并启用生态市场配置")
    @PostMapping("/updateAndEnable")
    public ReqResult<EcoMarketClientPublishConfigModel> updateAndEnableConfig(
            @RequestBody UpdateAndEnableConfigReqDTO reqDTO) {
        log.info("Update save-and-enable config: reqDTO={}", reqDTO);

        // 参数校验
        if (reqDTO == null || reqDTO.getUid() == null || reqDTO.getUid().isEmpty()) {
            throw BizException.of(BizExceptionCodeEnum.fieldRequiredButEmpty, "uid");
        }

        // 获取当前用户上下文
        var userContext = getUser();

        // 调用应用服务更新并启用配置
        EcoMarketClientPublishConfigModel result = ecoMarketClientPublishConfigApplicationService
                .updateAndEnableConfig(reqDTO, userContext);

        return ReqResult.success(result);

    }

    /**
     * 禁用配置
     *
     * @param uid 配置唯一标识
     * @return 禁用结果
     */
    @OperationLogReporter(actionType = ActionType.MODIFY, action = "禁用配置", objectName = "生态市场客户端配置", systemCode = SystemEnum.ECO_MARKET)
    @Operation(summary = "禁用配置", description = "禁用生态市场配置")
    @PostMapping("/disable")
    public ReqResult<EcoMarketClientPublishConfigModel> disableConfig(
            @Parameter(description = "配置唯一标识") @RequestParam("uid") String uid) {
        log.info("Disable config: uid={}", uid);

        // 获取当前用户上下文
        var userContext = getUser();

        // 调用应用服务禁用配置
        EcoMarketClientPublishConfigModel result = ecoMarketClientPublishConfigApplicationService.disableConfig(uid,
                userContext);

        return ReqResult.success(result);

    }
}
