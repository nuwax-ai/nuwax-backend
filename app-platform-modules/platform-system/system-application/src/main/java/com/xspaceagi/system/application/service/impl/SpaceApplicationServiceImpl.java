package com.xspaceagi.system.application.service.impl;

import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.SpaceUserDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.domain.service.SpaceDomainService;
import com.xspaceagi.system.domain.service.UserDomainService;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.infra.dao.entity.SpaceUser;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.SpacePermissionException;
import com.xspaceagi.system.spec.utils.I18nUtil;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SpaceApplicationServiceImpl implements SpaceApplicationService, SpacePermissionService {

    /**
     * 「空间列表」这类展示型入口允许出现的空间类型白名单。
     * <p>用白名单而不是「排除 DM」：{@code Group} 同样不该出现在这些列表里，且 {@link Space.Type} 日后再新增取值时
     * 默认不展示（安全的那一侧）。
     */
    private static final List<Space.Type> LIST_VISIBLE_TYPES = List.of(Space.Type.Personal, Space.Type.Team);

    @Resource
    private SpaceDomainService spaceDomainService;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private UserDomainService userDomainService;

    @Resource
    private RedisUtil redisUtil;

    @Override
    public Long add(SpaceDto spaceDto) {
        Space space = new Space();
        BeanUtils.copyProperties(spaceDto, space);
        spaceDomainService.add(space);
        spaceDomainService.addSpaceUser(SpaceUser.builder().spaceId(space.getId()).userId(space.getCreatorId()).role(SpaceUser.Role.Owner).build());
        return space.getId();
    }

    @Override
    public void addSpaceUser(SpaceUserDto spaceUserDto) {
        UserDto userDto = userApplicationService.queryById(spaceUserDto.getUserId());
        if (userDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemUserIdInvalid);
        }
        spaceDomainService.addSpaceUser(SpaceUser.builder().spaceId(spaceUserDto.getSpaceId()).userId(spaceUserDto.getUserId()).role(spaceUserDto.getRole()).build());
    }

    @Override
    public void delete(Long spaceId) {
        spaceDomainService.delete(spaceId);
        // 双队列分离消费：delete_space_queue 清 publish（agent 侧），delete_space_repo_queue 清 repo 文档（repo 侧）。
        // Redis 队列是「抢」语义，两个消费者共用一个队列会漏处理，故各 push 一条。
        redisUtil.leftPush("delete_space_queue", spaceId);
        redisUtil.leftPush("delete_space_repo_queue", spaceId);
    }

    @Override
    public void deleteSpaceUser(Long spaceId, Long userId) {
        spaceDomainService.deleteSpaceUser(spaceId, userId);
    }

    @Override
    public void transfer(Long spaceId, Long targetUserId) {
        spaceDomainService.transfer(spaceId, targetUserId);
    }

    @Override
    public void update(SpaceDto spaceDto) {
        Space space = new Space();
        BeanUtils.copyProperties(spaceDto, space);
        spaceDomainService.update(space);
    }

    @Override
    public void updateSpaceUserRole(Long spaceId, Long userId, SpaceUser.Role role) {
        spaceDomainService.updateSpaceUser(SpaceUser.builder().spaceId(spaceId).userId(userId).role(role).build());
    }

    @Override
    public SpaceDto queryById(Long spaceId) {
        Space space = spaceDomainService.queryById(spaceId);
        if (space == null) {
            return null;
        }
        SpaceDto spaceDto = new SpaceDto();
        BeanUtils.copyProperties(space, spaceDto);
        I18nUtil.replaceSystemMessage(spaceDto);
        return spaceDto;
    }

    @Override
    public List<SpaceDto> queryByIds(List<Long> spaceIds) {
        List<SpaceDto> spaceDtoList = spaceDomainService.queryListByIds(spaceIds).stream().map(space -> {
            SpaceDto spaceDto = new SpaceDto();
            BeanUtils.copyProperties(space, spaceDto);
            return spaceDto;
        }).toList();
        I18nUtil.replaceSystemMessage(spaceDtoList);
        return spaceDtoList;
    }

    @Override
    public List<SpaceDto> queryListByUserId(Long userId) {
        return queryVisibleListByUserId(userId);
    }

    @Override
    public List<SpaceDto> queryVisibleListByUserId(Long userId) {
        List<SpaceUser> spaceUsers = spaceDomainService.querySpaceUserListByUserId(userId);
        if (spaceUsers.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, SpaceUser> spaceUserMap = spaceUsers.stream().collect(Collectors.toMap(SpaceUser::getSpaceId, (s1) -> s1, (s1, s2) -> s1));
        List<Long> ids = spaceUsers.stream().map(SpaceUser::getSpaceId).toList();
        // 类型白名单下推到 SQL：私聊/群组空间整行都不查回来，避免「名下几千个 DM」时白读一遍再在内存里丢弃
        List<Space> spaceList = spaceDomainService.queryListByIdsAndTypes(ids, LIST_VISIBLE_TYPES);
        return spaceList.stream().map(space -> {
            SpaceDto spaceDto = new SpaceDto();
            BeanUtils.copyProperties(space, spaceDto);
            if (spaceUserMap.get(spaceDto.getId()) != null) {
                spaceDto.setCurrentUserRole(spaceUserMap.get(spaceDto.getId()).getRole());
            }
            return spaceDto;
        }).collect(Collectors.toList());
    }

    @Override
    public Long getPersonalSpaceId(Long userId) {
        Space personalSpace = null;
        List<Space> spaceList = spaceDomainService.queryListByIds(spaceDomainService.querySpaceUserListByUserId(userId).stream().map(SpaceUser::getSpaceId).toList());
        if (spaceList != null) {
            personalSpace = spaceList.stream()
                    .filter(s -> s.getType() == Space.Type.Personal)
                    .findFirst()
                    .orElse(null);
        }
        if (personalSpace == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentUserNoPersonalSpace);
        }
        return personalSpace.getId();
    }

    @Override
    public List<SpaceUserDto> querySpaceUserList(Long spaceId) {
        List<SpaceUserDto> spaceUsers = new ArrayList<>();
        List<SpaceUser> spaceUserList = spaceDomainService.querySpaceUserList(spaceId);
        if (spaceUserList != null && !spaceUserList.isEmpty()) {
            List<UserDto> userDtos = userApplicationService.queryUserListByIds(spaceUserList.stream().map(SpaceUser::getUserId).collect(Collectors.toList()));
            Map<Long, UserDto> userMap = userDtos.stream().collect(Collectors.toMap(UserDto::getId, user -> user, (u1, u2) -> u1));
            spaceUserList.forEach(spaceUser -> {
                UserDto userDto = userMap.get(spaceUser.getUserId());
                if (userDto == null || !"user".equals(userDto.getAccountType())) {
                    return;
                }
                SpaceUserDto spaceUserDto = new SpaceUserDto();
                BeanUtils.copyProperties(spaceUser, spaceUserDto);
                spaceUserDto.setUserName(userDto.getUserName());
                spaceUserDto.setNickName(userDto.getNickName());
                spaceUserDto.setAvatar(userDto.getAvatar());
                spaceUsers.add(spaceUserDto);
            });
        }
        return spaceUsers;
    }

    @Override
    public SpaceUserDto querySpaceUser(Long spaceId, Long userId) {
        SpaceUser spaceUser = spaceDomainService.querySpaceUser(spaceId, userId);
        if (spaceUser != null) {
            SpaceUserDto spaceUserDto = new SpaceUserDto();
            BeanUtils.copyProperties(spaceUser, spaceUserDto);
            return spaceUserDto;
        }
        return null;
    }

    @Override
    public String getSpaceUserRole(Long spaceId, Long userId) {
        SpaceUserDto spaceUserDto = querySpaceUser(spaceId, userId);
        if (spaceUserDto != null && spaceUserDto.getRole() != null) {
            return spaceUserDto.getRole().name();
        }
        // 与 checkSpaceUserPermission 保持一致:平台管理员视为拥有空间权限(按 Admin 处理)
        User user = userDomainService.queryById(userId);
        if (user != null && user.getRole() == User.Role.Admin
                && spaceDomainService.queryById(spaceId) != null) {
            return SpaceUser.Role.Admin.name();
        }
        return null;
    }


    public void checkSpaceAdminPermission(Long id) {
        SpaceUserDto spaceUserDto = querySpaceUser(id, RequestContext.get().getUserId());
        if (spaceUserDto == null || (spaceUserDto.getRole() != SpaceUser.Role.Admin && spaceUserDto.getRole() != SpaceUser.Role.Owner)) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }
    }

    public void checkSpaceOwnerPermission(Long id) {
        SpaceUserDto spaceUserDto = querySpaceUser(id, RequestContext.get().getUserId());
        if (spaceUserDto == null || (spaceUserDto.getRole() != SpaceUser.Role.Owner)) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }
    }

    public void checkSpaceUserPermission(Long id) {
        checkSpaceUserPermission(id, RequestContext.get().getUserId());
    }

    @Override
    public void checkSpaceUserPermission(Long id, Long userId) {
        UserDto userDto = (UserDto) RequestContext.get().getUser();
        if (userDto != null && userDto.getRole() == User.Role.Admin) {
            Space space = spaceDomainService.queryById(id);
            if (space != null) {
                return;
            } else {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemSpaceNotFound);
            }
        }
        SpaceUserDto spaceUserDto = querySpaceUser(id, userId);
        if (spaceUserDto == null) {
            //throw new BizException("没有权限");
            throw new SpacePermissionException();
        }
    }

    @Override
    public List<Long> querySpaceIdList(Long userId) {
        List<SpaceUser> spaceUserList = this.spaceDomainService.querySpaceUserListByUserId(userId);
        return spaceUserList.stream().map(SpaceUser::getSpaceId).collect(Collectors.toList());
    }

    @Override
    public Long countTotalSpaces() {
        return spaceDomainService.countTotalSpaces();
    }

    @Override
    public Long countUserCreatedTeamSpaces(Long userId) {
        return spaceDomainService.countUserCreatedTeamSpaces(userId);
    }
}
