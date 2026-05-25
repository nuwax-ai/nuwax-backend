package com.xspaceagi.custompage.ui.web.controller;

import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.infra.dao.entity.User;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BaseController {

    /**
     * 获取当前登录用户信息
     */
    public UserContext getUser() {
        var userDto = (UserDto) RequestContext.get().getUser();
        if (userDto == null) {
            throw BizException.of(ErrorCodeEnum.UNAUTHORIZED, BizExceptionCodeEnum.systemUserNotLoggedInWeb);
        }
        return UserContext.builder()
                .userId(userDto.getId())
                .uid(userDto.getUid())
                .userName(userDto.getUserName())
                .nickName(userDto.getNickName())
                .avatar(userDto.getAvatar())
                .email(userDto.getEmail())
                .phone(userDto.getPhone())
                .status(userDto.getStatus() == User.Status.Enabled ? 1 : -1)
                .tenantId(userDto.getTenantId())
                .tenantName(null)
                .orgId(null)
                .orgName(null)
                .roleType(userDto.getRole() == User.Role.Admin ? 1 : 2)
                .tenantConfig(RequestContext.get().getTenantConfig())
                .build();
    }

}
