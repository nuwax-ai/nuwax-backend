package com.xspaceagi.eco.market.web.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.google.common.eventbus.EventBus;
import com.xspaceagi.eco.market.domain.model.EcoMarketClientConfigModel;
import com.xspaceagi.eco.market.domain.specification.EcoMarkerSecretWrapper;
import com.xspaceagi.eco.market.sdk.model.ClientSecretDTO;
import com.xspaceagi.eco.market.spec.app.dto.request.ClientConfigQueryRequest;
import com.xspaceagi.eco.market.spec.app.service.IEcoMarketClientConfigApplicationService;
import com.xspaceagi.eco.market.spec.constant.EcoMarketApiConstant;
import com.xspaceagi.eco.market.web.controller.base.BaseController;
import com.xspaceagi.eco.market.web.controller.dto.req.ClientConfigDetailReqDTO;
import com.xspaceagi.eco.market.web.controller.dto.req.ClientConfigSaveReqDTO;
import com.xspaceagi.eco.market.web.controller.dto.req.ClientConfigUpdateDraftReqDTO;
import com.xspaceagi.eco.market.web.controller.dto.req.ClientConfigUpdateReqDTO;
import com.xspaceagi.eco.market.web.controller.dto.resp.ClientConfigVo;
import com.xspaceagi.system.sdk.operate.ActionType;
import com.xspaceagi.system.sdk.operate.OperationLogReporter;
import com.xspaceagi.system.sdk.operate.SystemEnum;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.event.PullMessageEvent;
import com.xspaceagi.system.spec.page.PageQueryVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Tag(name = "生态市场-客户端-配置")
@RestController
@RequestMapping(EcoMarketApiConstant.ClientConfig.BASE)
@Slf4j
public class EcoMarketClientConfigController extends BaseController {

    @Resource
    private IEcoMarketClientConfigApplicationService ecoMarketClientConfigApplicationService;

    @Resource
    private EcoMarkerSecretWrapper ecoMarkerSecretWrapper;

    @Resource
    private EventBus eventBus;

    @OperationLogReporter(actionType = ActionType.QUERY, action = "客户端配置列表查询", objectName = "生态市场客户端配置", systemCode = SystemEnum.ECO_MARKET)
    @Operation(summary = "客户端配置列表查询", description = "分页查询客户端配置列表，并比对服务器配置版本")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<IPage<ClientConfigVo>> list(
            @RequestBody PageQueryVo<ClientConfigQueryRequest> pageQueryVo) {
        log.info("Query client config list: {}", pageQueryVo);
        var userContext = getUser();
        // 获取或注册客户端密钥
        ClientSecretDTO clientSecret = ecoMarkerSecretWrapper.obtainClientSecretOrRegister(userContext.getTenantId());
        if (clientSecret == null) {
            return ReqResult.error("获取客户端密钥失败，请稍后重试");
        }
        // 执行分页查询，并比对服务器配置
        IPage<EcoMarketClientConfigModel> modelPage = this.ecoMarketClientConfigApplicationService
                .pageQueryWithServerCompare(pageQueryVo.getQueryFilter(), pageQueryVo.getCurrent(),
                        pageQueryVo.getPageSize(), clientSecret, userContext);

        var superPage = modelPage.convert(model -> ClientConfigVo.convert2Dto(model));

        // 注册成功后, 触发EventBus事件，异步拉取消息
        var clientId = clientSecret.getClientId();
        var pullMessageEvent = PullMessageEvent.builder()
                .userId(RequestContext.get().getUserId())
                .tenantId(RequestContext.get().getTenantId())
                .clientId(clientId)
                .build();
        eventBus.post(pullMessageEvent);

        return ReqResult.success(superPage);
    }

    @OperationLogReporter(actionType = ActionType.QUERY, action = "客户端配置详情查询", objectName = "生态市场客户端配置", systemCode = SystemEnum.ECO_MARKET)
    @Operation(summary = "客户端配置详情查询", description = "根据UID查询客户端配置详情")
    @PostMapping("/detail")
    public ReqResult<ClientConfigVo> getDetail(@RequestBody @Valid ClientConfigDetailReqDTO reqDTO) {
        log.info("Query client config detail: uid={}", reqDTO.getUid());

        // 获取当前用户上下文
        var userContext = getUser();
        var uid = reqDTO.getUid();
        // 获取或注册客户端密钥
        ClientSecretDTO clientSecret = ecoMarkerSecretWrapper.obtainClientSecretOrRegister(userContext.getTenantId());
        if (clientSecret == null) {
            return ReqResult.error("获取客户端密钥失败，请稍后重试");
        }

        // 调用应用服务获取配置详情
        EcoMarketClientConfigModel configModel = ecoMarketClientConfigApplicationService.getConfigDetail(
                uid, userContext);

        if (configModel == null) {
            return ReqResult.error("未找到对应配置信息");
        }

        // 转换为响应DTO
        ClientConfigVo respDTO = ClientConfigVo.convert2Dto(configModel);

        return ReqResult.success(respDTO);
    }

    @OperationLogReporter(actionType = ActionType.ADD, action = "新增草稿", objectName = "生态市场客户端配置", systemCode = SystemEnum.ECO_MARKET)
    @Operation(summary = "新增草稿", description = "创建客户端配置草稿")
    @PostMapping("/save/draft")
    public ReqResult<ClientConfigVo> saveDraft(@RequestBody @Valid ClientConfigSaveReqDTO reqDTO) {
        log.info("Save client config draft: {}", reqDTO);

        // 获取当前用户上下文
        var userContext = getUser();

        // 获取或注册客户端密钥
        ClientSecretDTO clientSecret = ecoMarkerSecretWrapper.obtainClientSecretOrRegister(userContext.getTenantId());
        if (clientSecret == null) {
            return ReqResult.error("获取客户端密钥失败，请稍后重试");
        }

        // 转换请求DTO为模型对象
        EcoMarketClientConfigModel model = ClientConfigSaveReqDTO.convert2Dto(reqDTO, userContext);

        // 调用应用服务创建草稿
        EcoMarketClientConfigModel configModel = ecoMarketClientConfigApplicationService.saveDraft(
                model, clientSecret.getClientId(), userContext);

        // 转换为响应DTO
        ClientConfigVo respDTO = ClientConfigVo.convert2Dto(configModel);

        return ReqResult.success(respDTO);
    }

    @OperationLogReporter(actionType = ActionType.MODIFY, action = "更新草稿", objectName = "生态市场客户端配置", systemCode = SystemEnum.ECO_MARKET)
    @Operation(summary = "更新草稿", description = "根据UID更新客户端配置草稿")
    @PostMapping("/update/draft")
    public ReqResult<ClientConfigVo> updateDraft(@RequestBody @Valid ClientConfigUpdateDraftReqDTO reqDTO) {
        log.info("Update client config draft: uid={}, name={}", reqDTO.getUid(), reqDTO.getName());

        // 获取当前用户上下文
        var userContext = getUser();

        // 获取或注册客户端密钥
        ClientSecretDTO clientSecret = ecoMarkerSecretWrapper.obtainClientSecretOrRegister(userContext.getTenantId());
        if (clientSecret == null) {
            return ReqResult.error("获取客户端密钥失败，请稍后重试");
        }

        // 构建模型
        EcoMarketClientConfigModel model = ClientConfigUpdateDraftReqDTO.convert2Dto(reqDTO, userContext);

        // 设置客户端ID
        model.setCreateClientId(clientSecret.getClientId());

        // 调用应用服务更新草稿
        EcoMarketClientConfigModel configModel = ecoMarketClientConfigApplicationService.updateDraft(model,
                userContext);

        // 转换为响应DTO
        ClientConfigVo respDTO = ClientConfigVo.convert2Dto(configModel);

        return ReqResult.success(respDTO);

    }

    @OperationLogReporter(actionType = ActionType.ADD, action = "保存并发布", objectName = "生态市场客户端配置", systemCode = SystemEnum.ECO_MARKET)
    @Operation(summary = "保存并发布", description = "保存客户端配置并提交审核")
    @PostMapping("/save/publish")
    public ReqResult<ClientConfigVo> saveAndPublish(@RequestBody @Valid ClientConfigSaveReqDTO reqDTO) {
        log.info("Save client config and publish: {}", reqDTO);

        // 获取当前用户上下文
        var userContext = getUser();

        // 获取或注册客户端密钥
        ClientSecretDTO clientSecret = ecoMarkerSecretWrapper.obtainClientSecretOrRegister(userContext.getTenantId());
        if (clientSecret == null) {
            return ReqResult.error("获取客户端密钥失败，请稍后重试");
        }

        // 构建模型
        EcoMarketClientConfigModel model = ClientConfigSaveReqDTO.convert2Dto(reqDTO, userContext);

        // 调用应用服务保存并发布
        EcoMarketClientConfigModel configModel = ecoMarketClientConfigApplicationService.saveAndPublish(
                model, clientSecret.getClientId(), clientSecret.getClientSecret(), userContext);

        // 转换为响应DTO
        ClientConfigVo respDTO = ClientConfigVo.convert2Dto(configModel);

        return ReqResult.success(respDTO);

    }

    @OperationLogReporter(actionType = ActionType.MODIFY, action = "更新并发布", objectName = "生态市场客户端配置", systemCode = SystemEnum.ECO_MARKET)
    @Operation(summary = "更新并发布", description = "更新客户端配置并提交审核")
    @PostMapping("/update/publish")
    public ReqResult<ClientConfigVo> updateAndPublish(@RequestBody @Valid ClientConfigUpdateReqDTO reqDTO) {
        log.info("Update client config and publish: {}", reqDTO);

        // 获取当前用户上下文
        var userContext = getUser();

        // 获取或注册客户端密钥
        ClientSecretDTO clientSecret = ecoMarkerSecretWrapper.obtainClientSecretOrRegister(userContext.getTenantId());
        if (clientSecret == null) {
            return ReqResult.error("获取客户端密钥失败，请稍后重试");
        }

        // 构建模型
        EcoMarketClientConfigModel model = ClientConfigUpdateReqDTO.convert2Dto(reqDTO, userContext);

        // 调用应用服务保存并发布
        EcoMarketClientConfigModel configModel = ecoMarketClientConfigApplicationService.saveAndPublish(
                model, clientSecret.getClientId(), clientSecret.getClientSecret(), userContext);

        // 转换为响应DTO
        ClientConfigVo respDTO = ClientConfigVo.convert2Dto(configModel);

        return ReqResult.success(respDTO);

    }

    @OperationLogReporter(actionType = ActionType.DELETE, action = "删除配置", objectName = "生态市场客户端配置", systemCode = SystemEnum.ECO_MARKET)
    @Operation(summary = "删除配置", description = "删除客户端配置")
    @PostMapping("/delete/{uid}")
    public ReqResult<Boolean> delete(@PathVariable("uid") String uid) {
        log.info("Delete client config: uid={}", uid);

        var userContext = getUser();
        // 获取或注册客户端密钥
        ClientSecretDTO clientSecret = ecoMarkerSecretWrapper.obtainClientSecretOrRegister(userContext.getTenantId());
        if (clientSecret == null) {
            return ReqResult.error("获取客户端密钥失败，请稍后重试");
        }

        // 调用应用服务删除配置
        boolean success = ecoMarketClientConfigApplicationService.deleteConfigByUid(uid, clientSecret, getUser());
        return ReqResult.success(success);

    }

    @OperationLogReporter(actionType = ActionType.MODIFY, action = "下线配置", objectName = "生态市场客户端配置", systemCode = SystemEnum.ECO_MARKET)
    @Operation(summary = "下线配置", description = "下线已发布的客户端配置")
    @PostMapping("/offline/{uid}")
    public ReqResult<ClientConfigVo> offline(@PathVariable("uid") String uid) {
        log.info("Offline client config: uid={}", uid);

        // 获取当前用户上下文
        var userContext = getUser();
        // 获取或注册客户端密钥
        ClientSecretDTO clientSecret = ecoMarkerSecretWrapper.obtainClientSecretOrRegister(userContext.getTenantId());
        if (clientSecret == null) {
            return ReqResult.error("获取客户端密钥失败，请稍后重试");
        }

        // 调用应用服务下线配置
        EcoMarketClientConfigModel configModel = ecoMarketClientConfigApplicationService.offlineConfigByUid(uid,
                clientSecret,
                userContext);

        // 转换为响应DTO
        ClientConfigVo respDTO = ClientConfigVo.convert2Dto(configModel);

        return ReqResult.success(respDTO);

    }

    @OperationLogReporter(actionType = ActionType.MODIFY, action = "撤销发布", objectName = "生态市场客户端配置", systemCode = SystemEnum.ECO_MARKET)
    @Operation(summary = "撤销发布", description = "撤销发布")
    @PostMapping("/unpublish/{uid}")
    public ReqResult<ClientConfigVo> unpublish(@PathVariable("uid") String uid) {
        log.info("Revoke publish client config: uid={}", uid);

        // 获取当前用户上下文
        var userContext = getUser();
        // 获取或注册客户端密钥
        ClientSecretDTO clientSecret = ecoMarkerSecretWrapper.obtainClientSecretOrRegister(userContext.getTenantId());
        if (clientSecret == null) {
            return ReqResult.error("获取客户端密钥失败，请稍后重试");
        }

        // 调用应用服务下线配置
        ecoMarketClientConfigApplicationService.unpublishConfigByUid(uid,
                clientSecret,
                userContext);
        return ReqResult.success();

    }

}
