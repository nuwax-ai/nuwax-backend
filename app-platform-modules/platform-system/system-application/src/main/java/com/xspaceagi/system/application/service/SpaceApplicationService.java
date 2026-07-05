package com.xspaceagi.system.application.service;

import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.SpaceUserDto;
import com.xspaceagi.system.infra.dao.entity.SpaceUser;

import java.util.List;

public interface SpaceApplicationService {

    Long add(SpaceDto spaceDto);

    void addSpaceUser(SpaceUserDto spaceUserDto);

    void delete(Long spaceId);

    void deleteSpaceUser(Long spaceId, Long userId);

    void transfer(Long spaceId, Long targetUserId);

    void update(SpaceDto spaceDto);

    void updateSpaceUserRole(Long spaceId, Long userId, SpaceUser.Role role);

    SpaceDto queryById(Long spaceId);

    List<SpaceDto> queryByIds(List<Long> spaceIds);

    /**
     * 查询用户的空间列表
     */
    List<SpaceDto> queryListByUserId(Long userId);

    /**
     * 查询用户的个人空间ID
     */
    Long getPersonalSpaceId(Long userId);

    /**
     * 查询空间用户列表
     */
    List<SpaceUserDto> querySpaceUserList(Long spaceId);

    /**
     * 查询空间用户
     *
     * @param spaceId
     * @param userId
     * @return
     */
    SpaceUserDto querySpaceUser(Long spaceId, Long userId);

    /**
     * 统计工作空间总数
     */
    Long countTotalSpaces();

    Long countUserCreatedTeamSpaces(Long userId);
}
