package com.xspaceagi.system.infra.dao.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xspaceagi.system.infra.dao.entity.AuthIdpConfig;

import java.util.List;

public interface AuthIdpConfigService extends IService<AuthIdpConfig> {

    /**
     * 查询已启用的IdP列表（按 sort、id 排序）
     */
    List<AuthIdpConfig> listEnabled();
}
