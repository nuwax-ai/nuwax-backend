package com.xspaceagi.system.application.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.system.spec.dto.PageQueryVo;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.dto.UserQueryDto;

import java.util.List;

public interface UserApplicationService {
    void add(UserDto userDto);

    void update(UserDto userDto);

    IPage<UserDto> listQuery(PageQueryVo<UserQueryDto> pageQueryVo);

    UserDto queryById(Long userId);

    UserDto queryUserByPhoneOrEmailWithPassword(String phoneOrEmail, String password);

    UserDto queryUserByPhone(String phone);

    UserDto queryUserByEmail(String email);

    UserDto queryUserByUserName(String userName);

    UserDto queryUserByUid(String uid);

    List<UserDto> queryUserListByIds(List<Long> userIds);


    /**
     * 根据用户UID列表查询用户列表,uid是字符串
     *
     * @param uids 用户UID列表,字符串
     * @return 用户列表
     */
    List<UserDto> queryUserListByUids(List<String> uids);

    /**
     * 查询用户ID列表，用于批量发送消息
     *
     * @param lastId
     * @param size
     * @return
     */
    List<Long> queryUserIdList(Long lastId, Integer size);

    String getUserDynamicCode(Long userId);

    void logicDelete(Long userId);
}
