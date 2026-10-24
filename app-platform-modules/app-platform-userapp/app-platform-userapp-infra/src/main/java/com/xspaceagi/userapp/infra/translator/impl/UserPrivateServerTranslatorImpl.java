package com.xspaceagi.userapp.infra.translator.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.userapp.domain.model.UserPrivateServerModel;
import com.xspaceagi.userapp.infra.dao.entity.UserPrivateServer;
import com.xspaceagi.userapp.infra.dao.vo.UserPrivateServerInfo;
import com.xspaceagi.userapp.infra.translator.IUserPrivateServerTranslator;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class UserPrivateServerTranslatorImpl implements IUserPrivateServerTranslator {

    @Override
    public UserPrivateServerModel convertToModel(UserPrivateServer entity) {
        if (entity == null) {
            return null;
        }
        UserPrivateServerModel model = new UserPrivateServerModel();
        model.setId(entity.getId());
        model.setTenantId(entity.getTenantId());
        model.setOwnerUserId(entity.getOwnerUserId());
        model.setName(entity.getName());
        model.setScheme(entity.getScheme());
        model.setHost(entity.getHost());
        applyServerInfo(model, entity.getServerInfo());
        model.setApiKey(entity.getApiKey());
        model.setStatus(entity.getStatus());
        model.setVersion(entity.getVersion());
        model.setLastHealthCheck(entity.getLastHealthCheck());
        model.setCreated(entity.getCreated());
        model.setModified(entity.getModified());
        return model;
    }

    @Override
    public UserPrivateServer convertToEntity(UserPrivateServerModel model) {
        if (model == null) {
            return null;
        }
        UserPrivateServer entity = new UserPrivateServer();
        entity.setId(model.getId());
        entity.setTenantId(model.getTenantId());
        entity.setOwnerUserId(model.getOwnerUserId());
        entity.setName(model.getName());
        entity.setScheme(model.getScheme());
        entity.setHost(model.getHost());
        // 三端口任一非空才写 server_info（null 保持稀疏更新不覆盖）
        if (model.getAgentPort() != null || model.getVncPort() != null || model.getFileServerPort() != null) {
            UserPrivateServerInfo info = new UserPrivateServerInfo();
            info.setAgentPort(model.getAgentPort());
            info.setVncPort(model.getVncPort());
            info.setFileServerPort(model.getFileServerPort());
            entity.setServerInfo(JSON.toJSONString(info));
        }
        entity.setApiKey(model.getApiKey());
        entity.setStatus(model.getStatus());
        entity.setVersion(model.getVersion());
        entity.setLastHealthCheck(model.getLastHealthCheck());
        entity.setCreated(model.getCreated());
        entity.setModified(model.getModified());
        return entity;
    }

    /** server_info JSON → 平铺端口；脏数据不阻断读取，仅告警留空 */
    private void applyServerInfo(UserPrivateServerModel model, String serverInfo) {
        if (StringUtils.isBlank(serverInfo)) {
            return;
        }
        try {
            UserPrivateServerInfo info = JSON.parseObject(serverInfo, UserPrivateServerInfo.class);
            if (info != null) {
                model.setAgentPort(info.getAgentPort());
                model.setVncPort(info.getVncPort());
                model.setFileServerPort(info.getFileServerPort());
            }
        } catch (Exception e) {
            log.warn("[UserPrivateServer] invalid server_info json, value={}", serverInfo, e);
        }
    }
}
