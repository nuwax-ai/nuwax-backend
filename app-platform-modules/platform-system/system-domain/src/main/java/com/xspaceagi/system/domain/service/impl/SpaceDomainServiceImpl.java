package com.xspaceagi.system.domain.service.impl;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xspaceagi.system.domain.service.SpaceDomainService;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.infra.dao.entity.SpaceUser;
import com.xspaceagi.system.infra.dao.service.SpaceService;
import com.xspaceagi.system.infra.dao.service.SpaceUserService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;

@Service
public class SpaceDomainServiceImpl implements SpaceDomainService {

    @Resource
    private SpaceService spaceService;

    @Resource
    private SpaceUserService spaceUserService;

    @Override
    public void add(Space space) {
        Assert.notNull(space, "space must be non-null");
        Assert.notNull(space.getName(), "name must be non-null");
        Assert.notNull(space.getCreatorId(), "creatorId must be non-null");
        spaceService.save(space);
    }

    @Override
    public void addSpaceUser(SpaceUser spaceUser) {
        Assert.notNull(spaceUser, "spaceUser must be non-null");
        Assert.notNull(spaceUser.getSpaceId(), "spaceId must be non-null");
        Assert.notNull(spaceUser.getUserId(), "userId must be non-null");
        Assert.notNull(spaceUser.getRole(), "role must be non-null");
        if (spaceUserService.exists(new QueryWrapper<>(SpaceUser.builder().spaceId(spaceUser.getSpaceId()).userId(spaceUser.getUserId()).build()))) {
            return;
        }
        spaceUserService.save(spaceUser);
    }

    @Override
    public void deleteSpaceUser(Long spaceId, Long userId) {
        Assert.notNull(spaceId, "spaceId must be non-null");
        Assert.notNull(userId, "userId must be non-null");
        QueryWrapper<SpaceUser> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("space_id", spaceId);
        queryWrapper.eq("user_id", userId);
        spaceUserService.remove(queryWrapper);
    }

    @Override
    public void delete(Long spaceId) {
        Assert.notNull(spaceId, "spaceId must be non-null");
        spaceService.removeById(spaceId);
        spaceUserService.remove(new QueryWrapper<>(SpaceUser.builder().spaceId(spaceId).build()));
    }

    @Override
    public void update(Space space) {
        Assert.notNull(space, "space must be non-null");
        Assert.notNull(space.getId(), "id must be non-null");
        spaceService.updateById(space);
    }

    @Override
    public void updateSpaceUser(SpaceUser spaceUser) {
        Assert.notNull(spaceUser, "spaceUser must be non-null");
        Assert.notNull(spaceUser.getId(), "id must be non-null");
        spaceUserService.updateById(spaceUser);
    }

    @Override
    public Space queryById(Long spaceId) {
        return spaceService.getById(spaceId);
    }

    @Override
    public List<Space> queryListByIds(List<Long> spaceIds) {
        Assert.notNull(spaceIds, "spaceIds must be non-null");
        if (spaceIds.isEmpty()) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<Space> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(Space::getId, spaceIds);
        return spaceService.list(queryWrapper);
    }

    @Override
    public List<Space> queryListByIdsAndTypes(List<Long> spaceIds, List<Space.Type> types) {
        Assert.notNull(spaceIds, "spaceIds must be non-null");
        if (spaceIds.isEmpty()) {
            return new ArrayList<>();
        }
        Assert.notEmpty(types, "types must be non-empty");
        LambdaQueryWrapper<Space> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(Space::getId, spaceIds);
        queryWrapper.in(Space::getType, types);
        return spaceService.list(queryWrapper);
    }

    @Override
    public List<SpaceUser> querySpaceUserList(Long spaceId) {
        Assert.notNull(spaceId, "spaceId must be non-null");
        LambdaQueryWrapper<SpaceUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SpaceUser::getSpaceId, spaceId);
        return spaceUserService.list(queryWrapper);
    }

    @Override
    public List<SpaceUser> querySpaceUserListByUserId(Long userId) {
        Assert.notNull(userId, "userId must be non-null");
        LambdaQueryWrapper<SpaceUser> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SpaceUser::getUserId, userId);
        return spaceUserService.list(queryWrapper);
    }

    @Override
    public SpaceUser querySpaceUser(Long spaceId, Long userId) {
        Assert.notNull(spaceId, "spaceId must be non-null");
        Assert.notNull(userId, "userId must be non-null");
        return spaceUserService.getOne(new QueryWrapper<>(SpaceUser.builder().spaceId(spaceId).userId(userId).build()));
    }

    @Override
    @DSTransactional
    public void transfer(Long spaceId, Long targetUserId) {
        Assert.notNull(spaceId, "spaceId must be non-null");
        Assert.notNull(targetUserId, "targetUserId must be non-null");
        Space space = spaceService.getById(spaceId);
        Long originUserId = space.getCreatorId();
        space.setCreatorId(targetUserId);
        spaceService.updateById(space);
        deleteSpaceUser(spaceId, targetUserId);
        deleteSpaceUser(spaceId, originUserId);
        addSpaceUser(SpaceUser.builder().spaceId(spaceId).userId(targetUserId).role(SpaceUser.Role.Owner).build());
        addSpaceUser(SpaceUser.builder().spaceId(spaceId).userId(originUserId).role(SpaceUser.Role.Admin).build());
    }

    @Override
    public Long countTotalSpaces() {
        return spaceService.count();
    }

    @Override
    public Long countUserCreatedTeamSpaces(Long userId) {
        LambdaQueryWrapper<Space> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Space::getCreatorId, userId);
        queryWrapper.eq(Space::getType, Space.Type.Team);
        return spaceService.count(queryWrapper);
    }
}
