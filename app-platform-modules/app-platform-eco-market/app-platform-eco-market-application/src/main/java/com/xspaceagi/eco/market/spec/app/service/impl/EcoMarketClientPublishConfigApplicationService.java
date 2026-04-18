package com.xspaceagi.eco.market.spec.app.service.impl;

import java.util.List;
import java.util.Map;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.xspaceagi.eco.market.domain.dto.req.UpdateAndEnableConfigReqDTO;
import org.springframework.stereotype.Service;

import com.xspaceagi.eco.market.domain.model.EcoMarketClientPublishConfigModel;
import com.xspaceagi.eco.market.domain.service.IEcoMarketClientConfigDomainService;
import com.xspaceagi.eco.market.domain.service.IEcoMarketClientPublishConfigDomainService;
import com.xspaceagi.eco.market.spec.app.service.IEcoMarketClientPublishConfigApplicationService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.EcoMarketException;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class EcoMarketClientPublishConfigApplicationService implements IEcoMarketClientPublishConfigApplicationService {

    @Resource
    private IEcoMarketClientPublishConfigDomainService ecoMarketClientPublishConfigDomainService;
    @Resource
    private IEcoMarketClientConfigDomainService ecoMarketClientConfigDomainService;

    @Override
    public EcoMarketClientPublishConfigModel queryOneInfoById(Long id) {
        return ecoMarketClientPublishConfigDomainService.queryOneInfoById(id);
    }

    @Override
    public List<EcoMarketClientPublishConfigModel> queryListByIds(List<Long> ids) {
        return ecoMarketClientPublishConfigDomainService.queryListByIds(ids);
    }

    @Override
    public List<EcoMarketClientPublishConfigModel> queryListByUids(List<String> uids) {
        return ecoMarketClientPublishConfigDomainService.queryListByUids(uids);
    }

    @Override
    public EcoMarketClientPublishConfigModel queryOneByUid(String uid) {
        log.info("Query client published config by uid: uid={}", uid);
        return ecoMarketClientPublishConfigDomainService.queryOneByUid(uid);
    }
    
    /**
     * 根据参数查询已发布配置列表
     * 
     * @param params 查询参数
     * @return 已发布配置列表
     */
    public List<EcoMarketClientPublishConfigModel> queryListByParams(Map<String, Object> params) {
        log.info("Query published configs: params={}", params);
        // 使用根据UID批量查询
        if (params != null && params.containsKey("uids")) {
            List<String> uids = (List<String>) params.get("uids");
            if (uids != null && !uids.isEmpty()) {
                // 查询UID对应的记录
                return uids.stream()
                    .map(uid -> ecoMarketClientPublishConfigDomainService.queryOneByUid(uid))
                    .filter(model -> model != null) // 过滤掉查询不到的记录
                    .toList();
            }
        }
        
        // 暂时返回空列表,可以后续扩展为通过仓库层查询
        return java.util.Collections.emptyList();
    }
    
    /**
     * 启用配置
     *
     * @param uid 配置唯一标识
     * @param userContext 用户上下文
     * @return 启用后的配置模型
     */
    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public EcoMarketClientPublishConfigModel enableConfig(String uid, UserContext userContext) {
        log.info("Application layer: enable config: uid={}", uid);
        
        if (uid == null || uid.isEmpty()) {
            throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "配置UID");
        }
        
        // 调用领域服务启用配置
        return ecoMarketClientPublishConfigDomainService.enableConfig(uid, userContext);
    }
    
    /**
     * 禁用配置
     *
     * @param uid 配置唯一标识
     * @param userContext 用户上下文
     * @return 禁用后的配置模型
     */
    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public EcoMarketClientPublishConfigModel disableConfig(String uid, UserContext userContext) {
        log.info("Application layer: disable config: uid={}", uid);
        
        if (uid == null || uid.isEmpty()) {
            throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "配置UID");
        }
        
        // 调用领域服务禁用配置
        return ecoMarketClientPublishConfigDomainService.disableConfig(uid, userContext);
    }

    /**
     * 更新并启用配置
     *
     * @param request 更新配置参数
     * @param userContext 用户上下文
     * @return 启用后的配置模型
     */
    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public EcoMarketClientPublishConfigModel updateAndEnableConfig(UpdateAndEnableConfigReqDTO request, UserContext userContext) {

        var uid = request.getUid();
        var configParamJson = request.getConfigParamJson();
        log.info("Application layer: update and enable config: uid={}, configParamJson={}", uid, configParamJson);
        
        if (uid == null || uid.isEmpty()) {
            throw EcoMarketException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "配置UID");
        }
        
        // 然后启用配置
        return ecoMarketClientPublishConfigDomainService.updateAndEnableConfig(request,userContext);
    }
}
