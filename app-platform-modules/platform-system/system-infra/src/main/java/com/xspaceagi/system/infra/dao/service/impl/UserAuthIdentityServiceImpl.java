package com.xspaceagi.system.infra.dao.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.system.infra.dao.entity.UserAuthIdentity;
import com.xspaceagi.system.infra.dao.mapper.UserAuthIdentityMapper;
import com.xspaceagi.system.infra.dao.service.UserAuthIdentityService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserAuthIdentityServiceImpl extends ServiceImpl<UserAuthIdentityMapper, UserAuthIdentity> implements UserAuthIdentityService {

    @Override
    public UserAuthIdentity getByIdpExternal(Long idpId, String externalId) {
        if (idpId == null || StringUtils.isBlank(externalId)) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<UserAuthIdentity>()
                .eq(UserAuthIdentity::getIdpId, idpId)
                .eq(UserAuthIdentity::getExternalId, externalId)
                .last("limit 1"));
    }

    @Override
    public UserAuthIdentity getFirstByTenantUnion(String unionId) {
        if (StringUtils.isBlank(unionId)) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<UserAuthIdentity>()
                .eq(UserAuthIdentity::getUnionId, unionId)
                .orderByAsc(UserAuthIdentity::getId)
                .last("limit 1"));
    }

    @Override
    public void deleteByUser(Long userId) {
        if (userId == null) {
            return;
        }
        remove(new LambdaQueryWrapper<UserAuthIdentity>().eq(UserAuthIdentity::getUserId, userId));
    }

    @Override
    public List<UserAuthIdentity> listByUser(Long userId) {
        return list(new LambdaQueryWrapper<UserAuthIdentity>()
                .eq(UserAuthIdentity::getUserId, userId)
                .orderByDesc(UserAuthIdentity::getCreated));
    }
}
