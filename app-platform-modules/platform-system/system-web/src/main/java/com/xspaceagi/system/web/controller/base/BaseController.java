package com.xspaceagi.system.web.controller.base;


import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import lombok.extern.slf4j.Slf4j;

/**
 * 公共逻辑处理
 *
 * @author soddy
 */
@Slf4j
public abstract class BaseController {

//    @Resource
//    private LoginWrapper loginWrapper;

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
                .build();
    }

//    /**
//     * 获取当前用户信息
//     */
//    public UserContext getUserInfo() {
//        var user = loginWrapper.getUser();
//        if (Objects.isNull(user)) {
//            user = UserContext.builder().build();
//        }
//        return user;
//    }
//
//    /**
//     * 获取当前用户id
//     */
//    public Long getUserId() {
//        return loginWrapper.getUserId();
//    }

}
