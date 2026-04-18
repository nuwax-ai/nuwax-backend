package com.xspaceagi.eco.market.spec.api.adaptor;

import com.alibaba.fastjson2.JSON;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.xspaceagi.eco.market.domain.client.EcoMarketServerApiService;
import com.xspaceagi.eco.market.domain.dto.req.UpdateAndEnableConfigReqDTO;
import com.xspaceagi.eco.market.domain.dto.resp.ServerConfigDetailRespDTO;
import com.xspaceagi.eco.market.domain.model.EcoMarketClientConfigModel;
import com.xspaceagi.eco.market.domain.model.EcoMarketClientPublishConfigModel;
import com.xspaceagi.eco.market.spec.app.service.IEcoMarketClientConfigApplicationService;
import com.xspaceagi.eco.market.spec.app.service.IEcoMarketClientPublishConfigApplicationService;
import com.xspaceagi.eco.market.spec.enums.EcoMarketOwnedFlagEnum;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.event.PullMessageEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 生态市场消息拉取组件
 * 使用Guava EventBus来异步处理消息拉取逻辑
 */
@Slf4j
@Component
public class EcoMarketPullMessage {

    @Resource
    private EventBus eventBus;

    @Resource
    private EcoMarketServerApiService ecoMarketServerApiService;

    @Resource
    private IEcoMarketClientPublishConfigApplicationService ecoMarketClientPublishConfigApplicationService;

    @Resource
    private IEcoMarketClientConfigApplicationService ecoMarketClientConfigApplicationService;

    @PostConstruct
    public void init() {
        // 注册到EventBus
        eventBus.register(this);
        log.info("EcoMarketPullMessage registered on EventBus");
    }

    /**
     * 监听消息拉取事件
     * 
     * @param event 消息拉取事件
     */
    @Subscribe
    public void handlePullMessageEvent(PullMessageEvent event) {
        log.info("Message pull event: {}", JSON.toJSONString(event));
        try {
            pullMessage(event.getUserId(), event.getTenantId());

        } catch (Exception e) {
            log.error("Message pull event handling failed", e);
        }
    }

    /**
     * 生态市场,租户自动启用配置
     * 
     * @param userId   用户ID
     * @param tenantId 租户ID
     */
    private void pullMessage(Long userId, Long tenantId) {
        log.info("Run message pull, userId: {}, tenantId: {}", userId, tenantId);

        var userContext = UserContext.builder()
                .userId(userId)
                .tenantId(tenantId)
                .userName("system")
                .build();

        try {
            // 设置请求上下文
            RequestContext<UserContext> requestContext = new RequestContext<>();
            requestContext.setTenantId(tenantId);
            requestContext.setUserContext(userContext);
            RequestContext.set(requestContext);

            // 查询自动启用配置列表
            List<ServerConfigDetailRespDTO> autoConfigList = ecoMarketServerApiService.queryAutoUseConfigList();

            log.debug("Eco-market tenant auto-enable: loaded auto-enable config list: {}", JSON.toJSONString(autoConfigList));

            // 使用结果中的 uid 来检查本地的生态市场配置,有无配置,如果没有,则主动启用,如果已经有了,但不做任何动作

            var autoUseUids = autoConfigList.stream().map(ServerConfigDetailRespDTO::getUid)
                    .collect(Collectors.toList());

            log.info("Eco-market tenant auto-enable: loaded auto-enable config list: {}", JSON.toJSONString(autoUseUids));

            // 检查本地有无启用记录,没有的话,一般需要启用
            var localConfigList = ecoMarketClientPublishConfigApplicationService.queryListByUids(autoUseUids);
            var localUids = localConfigList.stream()
                    .map(EcoMarketClientPublishConfigModel::getUid)
                    .collect(Collectors.toList());

            // 还要检查是否是我的分享,如果是我的分享,则不处理,因为我自己分享的,不需要自动启用
            var myShareConfigList = ecoMarketClientConfigApplicationService.queryListByUids(autoUseUids);
            var myShareUids = myShareConfigList.stream()
                    .filter(config -> EcoMarketOwnedFlagEnum.YES.getCode().equals(config.getOwnedFlag()))
                    .map(EcoMarketClientConfigModel::getUid)
                    .collect(Collectors.toList());

            // 如果本地没有配置,则主动启用..不管本地配置是否实际启用,只要有记录了,就不在操作自动启用,需要用户自己去手动操作
            var needEnableUids = autoUseUids.stream()
                    .filter(uid -> !localUids.contains(uid))
                    .filter(uid -> !myShareUids.contains(uid))
                    .collect(Collectors.toList());

            log.info("Eco-market tenant auto-enable, need-enable list: {}", JSON.toJSONString(needEnableUids));

            for (var uid : needEnableUids) {

                var configOptional = autoConfigList.stream()
                        .filter(config -> config.getUid().equals(uid))
                        .findFirst();
                if (configOptional.isEmpty()) {
                    log.warn("Eco-market auto-enable: no config, uid: {}", uid);
                    continue;
                }
                var configJson = configOptional.get().getConfigJson();
                if (configJson == null) {
                    log.warn("Eco-market auto-enable: empty config params, uid: {}", uid);
                    continue;
                }
                var configParamJson = configOptional.get().getConfigParamJson();
                try {
                    var updateAndEnableConfigReqDTO = new UpdateAndEnableConfigReqDTO();
                    updateAndEnableConfigReqDTO.setUid(uid);
                    updateAndEnableConfigReqDTO.setConfigJson(configJson);
                    updateAndEnableConfigReqDTO.setConfigParamJson(configParamJson);
                    ecoMarketClientPublishConfigApplicationService.updateAndEnableConfig(updateAndEnableConfigReqDTO,
                            userContext);
                    log.info("Eco-market tenant auto-enable, update-and-enable OK, uid: {}", uid);
                } catch (Exception e) {
                    log.error("Eco-market tenant auto-enable, update-and-enable failed, uid: {}, configParamJson: {}", uid, configParamJson, e);

                }
            }

            log.info("Eco-market auto-enable done, userId: {}, tenantId: {}", userId, tenantId);
        } catch (Exception e) {
            log.error("Eco-market auto-enable error, userId: {}, tenantId: {}", userId, tenantId, e);
        } finally {
            RequestContext.remove();
        }
    }

}
