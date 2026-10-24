package com.xspaceagi.system.application.service.impl;

import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.dto.UserIdentityVo;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.application.service.UserIdentityApplicationService;
import com.xspaceagi.system.infra.dao.entity.AuthIdpConfig;
import com.xspaceagi.system.infra.dao.entity.UserAuthIdentity;
import com.xspaceagi.system.infra.dao.service.AuthIdpConfigService;
import com.xspaceagi.system.infra.dao.service.UserAuthIdentityService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.exception.BizException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserIdentityApplicationServiceImpl implements UserIdentityApplicationService {

    @Resource
    private UserAuthIdentityService userAuthIdentityService;

    @Resource
    private AuthIdpConfigService authIdpConfigService;

    @Resource
    private UserApplicationService userApplicationService;

    @Override
    public List<UserIdentityVo> listMine() {
        UserDto user = requireCurrentUser();
        List<UserAuthIdentity> identities = userAuthIdentityService.listByUser(user.getId());
        Map<Long, AuthIdpConfig> configMap = identities.isEmpty() ? Map.of()
                : authIdpConfigService.listByIds(
                        identities.stream().map(UserAuthIdentity::getIdpId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(AuthIdpConfig::getId, config -> config));
        return identities.stream().map(identity -> {
            UserIdentityVo vo = new UserIdentityVo();
            vo.setId(identity.getId());
            vo.setIdpId(identity.getIdpId());
            vo.setIdpType(identity.getIdpType());
            AuthIdpConfig config = configMap.get(identity.getIdpId());
            vo.setProviderName(config == null ? null : config.getName());
            vo.setExternalUserName(identity.getExternalUserName());
            vo.setExternalIdMasked(maskExternalId(identity.getExternalId()));
            vo.setLastLoginTime(identity.getLastLoginTime());
            vo.setCreated(identity.getCreated());
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public void unbind(Long identityId) {
        UserDto user = requireCurrentUser();
        UserAuthIdentity identity = userAuthIdentityService.getById(identityId);
        if (identity == null || !Objects.equals(identity.getUserId(), user.getId())) {
            throw new BizException("0001", "绑定关系不存在");
        }
        Long count = userAuthIdentityService.lambdaQuery()
                .eq(UserAuthIdentity::getUserId, user.getId())
                .count();
        UserDto fullUser = userApplicationService.queryById(user.getId());
        // 唯一绑定且从未设置过密码：解绑会把自己锁在门外
        if (count != null && count <= 1
                && (fullUser == null || fullUser.getResetPass() == null || fullUser.getResetPass() == 0)) {
            throw new BizException("0001", "这是您当前唯一的登录方式，请先设置账号密码后再解绑");
        }
        userAuthIdentityService.removeById(identityId);
        log.info("identity unbound, identityId={}, userId={}", identityId, user.getId());
    }

    private static String maskExternalId(String externalId) {
        if (StringUtils.isBlank(externalId)) {
            return "";
        }
        if (externalId.length() <= 5) {
            return externalId.charAt(0) + "****";
        }
        return externalId.substring(0, 3) + "****" + externalId.substring(externalId.length() - 2);
    }

    private static UserDto requireCurrentUser() {
        Object user = RequestContext.get().getUser();
        if (!(user instanceof UserDto userDto)) {
            throw new BizException("4010", "请先登录");
        }
        return userDto;
    }
}
