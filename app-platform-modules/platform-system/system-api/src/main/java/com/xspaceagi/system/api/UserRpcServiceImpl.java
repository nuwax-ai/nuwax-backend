package com.xspaceagi.system.api;

import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.domain.service.UserDomainService;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.sdk.server.IUserRpcService;
import com.xspaceagi.system.sdk.service.dto.UserDetailDto;
import com.xspaceagi.system.spec.common.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 用户服务查询接口实现
 */
@Slf4j
@Service
public class UserRpcServiceImpl implements IUserRpcService {


    @Resource
    private UserDomainService userDomainService;
    @Autowired
    private UserApplicationService userApplicationService;


    @Override
    public List<UserContext> queryUserListByIds(List<Long> userIds) {
        if (CollectionUtils.isEmpty(userIds)) {
            return List.of();
        }

        var userList = this.userDomainService.queryUserListByIds(userIds);

        var userInfoList = userList.stream().map(this::convertFromUser).toList();


        return userInfoList;
    }

    @Override
    public UserDetailDto queryUserDetailByName(String userName) {
        User user = userDomainService.queryByUserName(userName);
        if (user == null) {
            user = userDomainService.queryByEmail(userName);
        }
        if (user == null) {
            user = userDomainService.queryByPhone(userName);
        }
        if (user != null) {
            UserDetailDto userDetailDto = new UserDetailDto();
            BeanUtils.copyProperties(user, userDetailDto);
            return userDetailDto;
        }
        return null;
    }

    @Override
    public UserDetailDto queryUserDetailById(Long userId) {
        User user = userDomainService.queryById(userId);
        if (user != null) {
            UserDetailDto userDetailDto = new UserDetailDto();
            BeanUtils.copyProperties(user, userDetailDto);
            return userDetailDto;
        }
        return null;
    }

    private UserContext convertFromUser(User user) {

        var status = User.Status.Enabled == user.getStatus() ? 1 : -1;

        return UserContext.builder()
                .userId(user.getId())
                .avatar(user.getAvatar())
                .userName(user.getUserName())
                .nickName(user.getNickName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .status(status)
                .orgId(null)
                .orgName(null)
                .roleType(1)
                .tenantId(user.getTenantId())
                .tenantName(null)
                .build();

    }

    @Override
    public String getUserDynamicCode(Long userId) {
        return userApplicationService.getUserDynamicCode(userId);
    }
}
