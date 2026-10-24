package com.xspaceagi.system.infra.dao.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.system.infra.dao.entity.AuthIdpConfig;
import com.xspaceagi.system.infra.dao.mapper.AuthIdpConfigMapper;
import com.xspaceagi.system.infra.dao.service.AuthIdpConfigService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuthIdpConfigServiceImpl extends ServiceImpl<AuthIdpConfigMapper, AuthIdpConfig> implements AuthIdpConfigService {

    @Override
    public List<AuthIdpConfig> listEnabled() {
        return list(new LambdaQueryWrapper<AuthIdpConfig>()
                .eq(AuthIdpConfig::getEnabled, 1)
                .orderByAsc(AuthIdpConfig::getSort, AuthIdpConfig::getId));
    }
}
