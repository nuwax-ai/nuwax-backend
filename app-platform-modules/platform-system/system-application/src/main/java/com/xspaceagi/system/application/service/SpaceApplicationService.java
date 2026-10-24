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
     * 查询用户的空间列表，<b>仅返回平台常规列表可见的类型</b>（个人空间 {@code Personal} + 团队空间 {@code Team}）。
     * <p>供「列表展示」类入口使用：页面端 {@code /api/space/list}、开放 API {@code /api/v1/space/list}、
     * 内部接口 {@code /api/internal/user/spaces}。私聊（{@code DM}）与群组（{@code Group}）是内部服务
     * 创建、供外部系统自行维护的空间，不出现在这些列表里。
     * <p>类型白名单<b>下推到 SQL</b>（{@code WHERE id IN (...) AND type IN ('Personal','Team')}），不是查回来再
     * 内存过滤 —— 一个用户名下可能有几千个私聊空间，整行读回来再丢弃是纯浪费。
     * <p><b>鉴权类用途请勿使用本方法</b>：凡「取 spaceId 集合做权限 / 可见性判断」的场景必须用
     * {@link #queryListByUserId(Long)}，否则会漏掉私聊 / 群组空间，造成越权。
     */
    List<SpaceDto> queryVisibleListByUserId(Long userId);

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
