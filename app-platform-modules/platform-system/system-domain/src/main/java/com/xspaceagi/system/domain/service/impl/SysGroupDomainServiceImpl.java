package com.xspaceagi.system.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.system.domain.model.GroupBindMenuModel;
import com.xspaceagi.system.domain.model.MenuNode;
import com.xspaceagi.system.domain.model.ResourceNode;
import com.xspaceagi.system.domain.model.SortIndex;
import com.xspaceagi.system.domain.service.*;
import com.xspaceagi.system.infra.dao.entity.*;
import com.xspaceagi.system.infra.dao.service.SysGroupMenuService;
import com.xspaceagi.system.infra.dao.service.SysGroupService;
import com.xspaceagi.system.infra.dao.service.SysUserGroupService;
import com.xspaceagi.system.infra.dao.service.UserService;
import com.xspaceagi.system.sdk.service.dto.TokenLimit;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.*;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.xspaceagi.system.spec.utils.CodeGeneratorUtil;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户组领域服务实现
 */
@Service
public class SysGroupDomainServiceImpl implements SysGroupDomainService {

    @Resource
    private UserService userService;
    @Resource
    private SysGroupService sysGroupService;
    @Resource
    private SysUserGroupService sysUserGroupService;
    @Resource
    private SysGroupMenuService sysGroupMenuService;
    @Resource
    private SysMenuDomainService sysMenuDomainService;
    @Resource
    private SysResourceDomainService sysResourceDomainService;
    @Resource
    private SysDataPermissionDomainService sysDataPermissionDomainService;
    @Resource
    private SysSubjectPermissionDomainService sysSubjectPermissionDomainService;
    @Resource
    private MenuBindResourceHelper menuBindResourceHelper;

    private void normalizeGroup(SysGroup group) {
        group.setCode(StringUtils.trim(group.getCode()));
        group.setName(StringUtils.trim(group.getName()));
        group.setDescription(StringUtils.trim(group.getDescription()));

        if (StringUtils.isNotBlank(group.getCode()) && !group.getCode().matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacCodeFormatInvalid);
        }
        if (StringUtils.length(group.getCode()) > 100) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacCodeLengthExceeded);
        }
        if (StringUtils.length(group.getName()) > 50) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacNameLengthExceeded);
        }
        if (StringUtils.length(group.getDescription()) > 500) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacDescLengthExceeded);
        }
    }

    @Override
    public void addGroup(SysGroup group, UserContext userContext) {
        if (StringUtils.isBlank(group.getName())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "名称");
        }
        if (group.getMaxUserCount() == null) {
            group.setMaxUserCount(-1);
        }

        // 如果编码为空，根据名称自动生成编码
        if (StringUtils.isBlank(group.getCode())) {
            String generatedCode = CodeGeneratorUtil.generateUniqueCodeFromName(
                group.getName(),
                "group_",
                code -> queryGroupByCode(code) != null
            );
            group.setCode(generatedCode);
        }

        normalizeGroup(group);

        SysGroup exist = queryGroupByCode(group.getCode());
        if (exist != null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGroupCodeDuplicate);
        }

        if (group.getSource() == null) {
            group.setSource(SourceEnum.CUSTOM.getCode());
        }
        if (group.getStatus() == null) {
            group.setStatus(StatusEnum.ENABLED.getCode());
        }
        if (group.getSortIndex() == null) {
            group.setSortIndex(0);
        }
        group.setTenantId(userContext.getTenantId());
        group.setCreatorId(userContext.getUserId());
        group.setCreator(userContext.getUserName());
        group.setYn(YnEnum.Y.getKey());
        sysGroupService.save(group);

        if (group.getId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacSaveFailed);
        }
    }

    @Override
    public boolean updateGroup(SysGroup group, UserContext userContext) {
        if (group.getId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "ID");
        }
        if (group.getMaxUserCount() == null) {
            group.setMaxUserCount(-1);
        }
        normalizeGroup(group);

        SysGroup exist = queryGroupById(group.getId());
        if (exist == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGroupNotFound);
        }
        if (SourceEnum.SYSTEM.getCode().equals(exist.getSource())) {
            if (group.getCode() != null && !group.getCode().equals(exist.getCode())) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemBuiltinGroupCodeImmutable);
            }
            if (group.getName() != null && !group.getName().equals(exist.getName())) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemBuiltinGroupNameImmutable);
            }
            if (group.getStatus() != null && !group.getStatus().equals(StatusEnum.ENABLED.getCode())) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemBuiltinGroupCannotDisable);
            }
        }

        if (group.getMaxUserCount() != null && group.getMaxUserCount() > -1) {
            // 校验最大用户数不能小于当前已绑定用户数
            long boundUserCount = sysUserGroupService.count(
                    Wrappers.<SysUserGroup>lambdaQuery()
                            .eq(SysUserGroup::getGroupId, group.getId())
                            .eq(SysUserGroup::getYn, YnEnum.Y.getKey()));
            if (group.getMaxUserCount() < boundUserCount) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGroupMaxUsersBelowBound,
                        String.valueOf(boundUserCount));
            }
        }

        boolean statusChanged = group.getStatus() != null
                && !Objects.equals(exist.getStatus(), group.getStatus());

        group.setModifierId(userContext.getUserId());
        group.setModifier(userContext.getUserName());
        sysGroupService.updateById(group);
        return statusChanged;
    }

    @Override
    public void bindDataPermission(Long groupId, SysDataPermission dataPermission, UserContext userContext) {
        if (groupId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "用户组ID");
        }
        SysGroup exist = queryGroupById(groupId);
        if (exist == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGroupNotFound);
        }
        if (dataPermission == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacDataPermissionRequired);
        }

        if (CollectionUtils.isNotEmpty(dataPermission.getModelIds())) {
            List<Long> modelIds = dataPermission.getModelIds().stream()
                    .filter(modelId -> Objects.nonNull(modelId) && modelId >= 1)
                    .collect(Collectors.toList());
            dataPermission.setModelIds(modelIds);
        }
        if (CollectionUtils.isNotEmpty(dataPermission.getAgentIds())) {
            List<Long> agentIds = dataPermission.getAgentIds().stream()
                    .filter(agentId -> Objects.nonNull(agentId) && agentId >= 1)
                    .toList();
            dataPermission.setAgentIds(agentIds);
        }
        if (CollectionUtils.isNotEmpty(dataPermission.getPageAgentIds())) {
            List<Long> pageAgentIds = dataPermission.getPageAgentIds().stream()
                    .filter(pageAgentId -> Objects.nonNull(pageAgentId) && pageAgentId >= 1)
                    .toList();
            dataPermission.setPageAgentIds(pageAgentIds);
        }
        if (dataPermission.getOpenApiConfigMap() != null && !dataPermission.getOpenApiConfigMap().isEmpty()) {
            Map<String, String> normalizedConfig = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : dataPermission.getOpenApiConfigMap().entrySet()) {
                if (StringUtils.isBlank(entry.getKey())) {
                    continue;
                }
                normalizedConfig.put(entry.getKey(), entry.getValue());
            }
            dataPermission.setOpenApiConfigMap(normalizedConfig);
        }
        if (CollectionUtils.isNotEmpty(dataPermission.getKnowledgeIds())) {
            List<Long> knowledgeIds = dataPermission.getKnowledgeIds().stream()
                    .filter(knowledgeId -> Objects.nonNull(knowledgeId) && knowledgeId >= 1)
                    .toList();
            dataPermission.setKnowledgeIds(knowledgeIds);
        }

        if (dataPermission.getTokenLimit() != null) {
            if (dataPermission.getTokenLimit().getLimitPerDay() < -1) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacTokenLimitMinInvalid);
            }
        } else {
            dataPermission.setTokenLimit(new TokenLimit(-1L));
        }

        dataPermission.setTargetType(PermissionTargetTypeEnum.GROUP.getCode());
        dataPermission.setTargetId(groupId);
        SysDataPermission oldDataPermission = sysDataPermissionDomainService.getByTarget(PermissionTargetTypeEnum.GROUP, groupId);
        if (oldDataPermission != null) {
            sysDataPermissionDomainService.update(oldDataPermission.getId(), dataPermission, userContext);
        } else {
            sysDataPermissionDomainService.add(dataPermission, userContext);
        }

        // 全量覆盖写入
        sysSubjectPermissionDomainService.replaceSubjectsByTargetAndSubjectIds(PermissionTargetTypeEnum.GROUP, groupId,
                PermissionSubjectTypeEnum.MODEL, dataPermission.getModelIds(), userContext);
        sysSubjectPermissionDomainService.replaceSubjectsByTargetAndSubjectIds(PermissionTargetTypeEnum.GROUP, groupId,
                PermissionSubjectTypeEnum.AGENT, dataPermission.getAgentIds(), userContext);
        sysSubjectPermissionDomainService.replaceSubjectsByTargetAndSubjectIds(PermissionTargetTypeEnum.GROUP, groupId,
                PermissionSubjectTypeEnum.PAGE, dataPermission.getPageAgentIds(), userContext);
        sysSubjectPermissionDomainService.replaceSubjectsByTargetAndSubjectKeyConfig(PermissionTargetTypeEnum.GROUP, groupId,
                PermissionSubjectTypeEnum.OPEN_API, dataPermission.getOpenApiConfigMap(), userContext);
        sysSubjectPermissionDomainService.replaceSubjectsByTargetAndSubjectIds(PermissionTargetTypeEnum.GROUP, groupId,
                PermissionSubjectTypeEnum.KNOWLEDGE, dataPermission.getKnowledgeIds(), userContext);
    }

    @Override
    public void batchUpdateGroupSort(List<SortIndex> sortIndexList, UserContext userContext) {
        if (CollectionUtils.isEmpty(sortIndexList)) {
            return;
        }
        for (SortIndex item : sortIndexList) {
            if (item == null || item.getId() == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "用户组ID");
            }
            SysGroup exist = queryGroupById(item.getId());
            if (exist == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGroupNotFoundWithRowId, item.getId());
            }
            if (item.getSortIndex() == null) {
                continue;
            }
            SysGroup updateGroup = new SysGroup();
            updateGroup.setId(item.getId());
            updateGroup.setSortIndex(item.getSortIndex());
            updateGroup.setModifierId(userContext.getUserId());
            updateGroup.setModifier(userContext.getUserName());
            sysGroupService.updateById(updateGroup);
        }
    }

    @Override
    public void deleteGroup(Long groupId, UserContext userContext) {
        if (groupId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "ID");
        }
        SysGroup exist = queryGroupById(groupId);
        if (exist == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGroupNotFound);
        }
        if (SourceEnum.SYSTEM.getCode().equals(exist.getSource())) {
            //throw new BizException("系统内置用户组不能删除");
        }

        // 删除用户组
        sysGroupService.removeById(groupId);
        // 删除数据权限
        sysDataPermissionDomainService.deleteByTaret(PermissionTargetTypeEnum.GROUP, groupId, userContext);
        // 删除组绑定的用户
        sysUserGroupService.remove(Wrappers.<SysUserGroup>lambdaUpdate().eq(SysUserGroup::getGroupId, groupId));
        // 删除组绑定的菜单
        sysGroupMenuService.remove(Wrappers.<SysGroupMenu>lambdaUpdate().eq(SysGroupMenu::getGroupId, groupId));
    }

    @Override
    public SysGroup queryGroupById(Long groupId) {
        LambdaQueryWrapper<SysGroup> wrapper = Wrappers.<SysGroup>lambdaQuery().eq(SysGroup::getId, groupId).eq(SysGroup::getYn, YnEnum.Y.getKey());
        return sysGroupService.getOne(wrapper);
    }

    @Override
    public List<SysGroup> queryGroupListByIds(List<Long> groupIds) {
        if (CollectionUtils.isEmpty(groupIds)) {
            return Collections.emptyList();
        }
        List<Long> ids = groupIds.stream().filter(Objects::nonNull).distinct().toList();
        return sysGroupService.list(Wrappers.<SysGroup>lambdaQuery()
                .in(SysGroup::getId, ids)
                .eq(SysGroup::getYn, YnEnum.Y.getKey()));
    }

    @Override
    public SysGroup queryGroupByCode(String groupCode) {
        LambdaQueryWrapper<SysGroup> wrapper = Wrappers.<SysGroup>lambdaQuery().eq(SysGroup::getCode, groupCode).eq(SysGroup::getYn, YnEnum.Y.getKey());
        return sysGroupService.getOne(wrapper);
    }

    @Override
    public List<SysGroup> queryGroupList(SysGroup group) {
        LambdaQueryWrapper<SysGroup> wrapper = Wrappers.<SysGroup>lambdaQuery()
                .eq(StringUtils.isNotBlank(group.getCode()), SysGroup::getCode, group.getCode())
                .like(StringUtils.isNotBlank(group.getName()), SysGroup::getName, group.getName())
                .eq(Objects.nonNull(group.getSource()), SysGroup::getSource, group.getSource())
                .eq(Objects.nonNull(group.getStatus()), SysGroup::getStatus, group.getStatus())
                .eq(SysGroup::getYn, YnEnum.Y.getKey()).orderByAsc(SysGroup::getSortIndex);
        return sysGroupService.list(wrapper);
    }

    @Override
    public List<User> getUserListByGroupId(Long groupId) {
        LambdaQueryWrapper<SysUserGroup> wrapper = Wrappers.<SysUserGroup>lambdaQuery().eq(SysUserGroup::getGroupId, groupId).eq(SysUserGroup::getYn, YnEnum.Y.getKey());
        List<SysUserGroup> list = sysUserGroupService.list(wrapper);
        if (CollectionUtils.isEmpty(list)) {
            return new ArrayList<>();
        }
        List<Long> userIdList = list.stream().map(SysUserGroup::getUserId).toList();
        List<User> users = userService.listByIds(userIdList);
        return users;
    }

    @Override
    public IPage<User> getUserPageByGroupId(Long groupId, String userName, long pageNo, long pageSize) {
        List<Long> matchingUserIds = null;
        if (StringUtils.isNotBlank(userName)) {
            List<User> matchingUsers = userService.list(Wrappers.<User>lambdaQuery()
                    .and(w -> w.like(User::getNickName, userName).or().like(User::getUserName, userName))
                    .select(User::getId));
            matchingUserIds = matchingUsers.stream().map(User::getId).filter(Objects::nonNull).toList();
            if (CollectionUtils.isEmpty(matchingUserIds)) {
                return new Page<>(pageNo, pageSize, 0);
            }
        }
        LambdaQueryWrapper<SysUserGroup> wrapper = Wrappers.<SysUserGroup>lambdaQuery()
                .eq(SysUserGroup::getGroupId, groupId)
                .eq(SysUserGroup::getYn, YnEnum.Y.getKey())
                .in(CollectionUtils.isNotEmpty(matchingUserIds), SysUserGroup::getUserId, matchingUserIds)
                .orderByAsc(SysUserGroup::getUserId);
        IPage<SysUserGroup> userGroupPage = sysUserGroupService.page(new Page<>(pageNo, pageSize), wrapper);
        if (CollectionUtils.isEmpty(userGroupPage.getRecords())) {
            return new Page<>(pageNo, pageSize, userGroupPage.getTotal());
        }
        List<Long> pageUserIds = userGroupPage.getRecords().stream().map(SysUserGroup::getUserId).filter(Objects::nonNull).toList();
        List<User> users = userService.listByIds(pageUserIds);
        Map<Long, User> userMap = users.stream().collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        List<User> orderedUsers = pageUserIds.stream().map(userMap::get).filter(Objects::nonNull).toList();
        Page<User> page = new Page<>(pageNo, pageSize, userGroupPage.getTotal());
        page.setRecords(orderedUsers);
        return page;
    }

    @Override
    public long countUsersByGroupId(Long groupId) {
        LambdaQueryWrapper<SysUserGroup> wrapper = Wrappers.<SysUserGroup>lambdaQuery()
                .eq(SysUserGroup::getGroupId, groupId)
                .eq(SysUserGroup::getYn, YnEnum.Y.getKey());
        return sysUserGroupService.count(wrapper);
    }

    @Override
    public List<Long> getUserIdsByGroupId(Long groupId) {
        LambdaQueryWrapper<SysUserGroup> wrapper = Wrappers.<SysUserGroup>lambdaQuery()
                .eq(SysUserGroup::getGroupId, groupId)
                .eq(SysUserGroup::getYn, YnEnum.Y.getKey())
                .select(SysUserGroup::getUserId);
        List<SysUserGroup> list = sysUserGroupService.list(wrapper);
        if (CollectionUtils.isEmpty(list)) {
            return new ArrayList<>();
        }
        return list.stream().map(SysUserGroup::getUserId).filter(Objects::nonNull).toList();
    }

    @Override
    public List<SysGroup> queryGroupListByUserId(Long userId) {
        // 先查询用户和组关联
        LambdaQueryWrapper<SysUserGroup> wrapper = Wrappers.<SysUserGroup>lambdaQuery()
                .eq(SysUserGroup::getUserId, userId)
                .eq(SysUserGroup::getYn, YnEnum.Y.getKey());
        List<SysUserGroup> userGroups = sysUserGroupService.list(wrapper);

        if (CollectionUtils.isEmpty(userGroups)) {
            return new ArrayList<>();
        }

        // 获取用户组ID列表
        List<Long> groupIds = userGroups.stream().map(SysUserGroup::getGroupId).collect(Collectors.toList());

        // 查询用户组信息
        LambdaQueryWrapper<SysGroup> groupWrapper = new LambdaQueryWrapper<>();
        groupWrapper.in(SysGroup::getId, groupIds)
                .eq(SysGroup::getYn, YnEnum.Y.getKey())
                .orderByAsc(SysGroup::getSortIndex);
        return sysGroupService.list(groupWrapper);
    }

    @Override
    public void groupBindUser(Long groupId, List<Long> userIds, UserContext userContext) {
        if (groupId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "组ID");
        }
        SysGroup sysGroup = queryGroupById(groupId);
        if (sysGroup == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGroupNotFoundWithGroupId, groupId);
        }
        if (CollectionUtils.isNotEmpty(userIds)
                && sysGroup.getMaxUserCount() != null
                && sysGroup.getMaxUserCount() > -1
                && userIds.size() > sysGroup.getMaxUserCount()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGroupUserLimitExceeded);
        }
        // 校验用户是否存在
        if (CollectionUtils.isNotEmpty(userIds)) {
            Set<Long> distinctUserIds = new HashSet<>(userIds);
            List<User> users = userService.listByIds(distinctUserIds);
            List<Long> existUserIds = users.stream().map(User::getId).toList();

            userIds.forEach(userId -> {
                if (!existUserIds.contains(userId)) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacUserNotFoundWithId, userId);
                }
            });
        }

        // 物理删除原绑定关系
        LambdaUpdateWrapper<SysUserGroup> wrapper = Wrappers.<SysUserGroup>lambdaUpdate().eq(SysUserGroup::getGroupId, groupId);
        sysUserGroupService.remove(wrapper);

        if (CollectionUtils.isEmpty(userIds)) {
            return;
        }

        List<SysUserGroup> userGroupList = userIds.stream().map(userId -> {
            SysUserGroup userGroup = new SysUserGroup();
            userGroup.setUserId(userId);
            userGroup.setGroupId(groupId);
            userGroup.setTenantId(userContext.getTenantId());
            userGroup.setCreatorId(userContext.getUserId());
            userGroup.setCreator(userContext.getUserName());
            userGroup.setYn(YnEnum.Y.getKey());
            return userGroup;
        }).toList();

        if (!CollectionUtils.isEmpty(userGroupList)) {
            sysUserGroupService.saveBatch(userGroupList);
        }
    }

    @Override
    public void groupAddUser(Long groupId, Long userId, UserContext userContext) {
        if (groupId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "组ID");
        }
        if (userId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "用户ID");
        }
        SysGroup sysGroup = queryGroupById(groupId);
        if (sysGroup == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGroupNotFoundWithGroupId, groupId);
        }
        User user = userService.getById(userId);
        if (user == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacUserNotFoundWithId, userId);
        }
        // 检查是否已绑定，避免重复插入
        LambdaQueryWrapper<SysUserGroup> queryWrapper = Wrappers.<SysUserGroup>lambdaQuery()
                .eq(SysUserGroup::getGroupId, groupId)
                .eq(SysUserGroup::getUserId, userId)
                .eq(SysUserGroup::getYn, YnEnum.Y.getKey());
        if (sysUserGroupService.count(queryWrapper) > 0) {
            return;
        }
        // 校验用户数量限制
        if (sysGroup.getMaxUserCount() != null && sysGroup.getMaxUserCount() > -1) {
            long currentCount = countUsersByGroupId(groupId);
            if (currentCount >= sysGroup.getMaxUserCount()) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGroupUserLimitExceeded);
            }
        }
        SysUserGroup userGroup = new SysUserGroup();
        userGroup.setUserId(userId);
        userGroup.setGroupId(groupId);
        userGroup.setTenantId(userContext.getTenantId());
        userGroup.setCreatorId(userContext.getUserId());
        userGroup.setCreator(userContext.getUserName());
        userGroup.setYn(YnEnum.Y.getKey());
        sysUserGroupService.save(userGroup);
    }

    @Override
    public void groupRemoveUser(Long groupId, Long userId, UserContext userContext) {
        if (groupId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "组ID");
        }
        if (userId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "用户ID");
        }
        LambdaUpdateWrapper<SysUserGroup> wrapper = Wrappers.<SysUserGroup>lambdaUpdate()
                .eq(SysUserGroup::getGroupId, groupId)
                .eq(SysUserGroup::getUserId, userId);
        sysUserGroupService.remove(wrapper);
    }

    @Override
    public void userBindGroup(Long userId, List<Long> groupIds, UserContext userContext) {
        if (userId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "用户ID");
        }
        User user = userService.getById(userId);
        if (user == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacUserNotFoundWithId, userId);
        }

        // 校验组是否存在
        if (CollectionUtils.isNotEmpty(groupIds)) {
            Set<Long> distinctGroupIds = new HashSet<>(groupIds);
            List<SysGroup> groups = sysGroupService.listByIds(distinctGroupIds);
            if (CollectionUtils.isEmpty(groups)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGroupNotFound);
            }
            Set<Long> existGroupIds = groups.stream().map(SysGroup::getId).collect(Collectors.toSet());
            groupIds.forEach(groupId -> {
                if (!existGroupIds.contains(groupId)) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGroupNotFoundWithGroupIdAlt, groupId);
                }
            });
        }

        // 物理删除原绑定关系
        LambdaUpdateWrapper<SysUserGroup> wrapper = Wrappers.<SysUserGroup>lambdaUpdate().eq(SysUserGroup::getUserId, userId);
        sysUserGroupService.remove(wrapper);

        if (CollectionUtils.isEmpty(groupIds)) {
            return;
        }

        List<SysUserGroup> userGroupList = groupIds.stream().map(groupId -> {
            SysUserGroup userGroup = new SysUserGroup();
            userGroup.setUserId(userId);
            userGroup.setGroupId(groupId);
            userGroup.setTenantId(userContext.getTenantId());
            userGroup.setCreatorId(userContext.getUserId());
            userGroup.setCreator(userContext.getUserName());
            userGroup.setYn(YnEnum.Y.getKey());
            return userGroup;
        }).toList();

        if (!CollectionUtils.isEmpty(userGroupList)) {
            sysUserGroupService.saveBatch(userGroupList);
        }
    }

    @Override
    public void bindMenu(GroupBindMenuModel model, UserContext userContext) {
        if (model == null || model.getGroupId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "用户组ID");
        }

        Long groupId = model.getGroupId();
        LambdaUpdateWrapper<SysGroupMenu> deleteWrapper = Wrappers.<SysGroupMenu>lambdaUpdate().eq(SysGroupMenu::getGroupId, groupId);
        sysGroupMenuService.remove(deleteWrapper);

        List<MenuNode> menuBindResourceList = model.getMenuBindResourceList();
        if (CollectionUtils.isEmpty(menuBindResourceList)) {
            return;
        }

        List<SysMenu> allMenus = sysMenuDomainService.queryMenuList(null);
        List<SysResource> allResources = sysResourceDomainService.queryResourceList(null);
        MenuBindResourceHelper.MenuResourceMaps maps = menuBindResourceHelper.buildMenuAndResourceMaps(allMenus, allResources);
        Map<Long, Integer> menuBindTypeMap = menuBindResourceHelper.buildAndPropagateMenuBindTypeMap(menuBindResourceList, maps.menuChildrenMap);

        // menuBindType=1(ALL) 表示该节点下所有子菜单均被绑定，但每个子菜单的资源绑定可能不同，需存每个子菜单并各自存 resource_tree_json
        List<SysGroupMenu> groupMenus = new ArrayList<>();
        for (MenuNode menuNode : menuBindResourceList) {
            Long menuId = menuNode.getId();
            if (menuId == null) {
                continue;
            }
            if (menuId != 0L && !maps.menuMap.containsKey(menuId)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacMenuIdNotFound, menuId);
            }
            Integer menuBindType = menuBindTypeMap.getOrDefault(menuId, BindTypeEnum.NONE.getCode());
            if (menuBindType == null || BindTypeEnum.NONE.getCode().equals(menuBindType)) {
                continue;
            }
            SysGroupMenu groupMenu = new SysGroupMenu();
            groupMenu.setGroupId(groupId);
            groupMenu.setMenuId(menuId);
            groupMenu.setMenuBindType(menuBindType);
            List<ResourceNode> processedResourceTree = menuBindResourceHelper.processResourceTreeForMenu(
                    menuId, menuNode.getResourceTree(), maps.resourceMap, maps.resourceChildrenMap);
            groupMenu.setResourceTreeJson(CollectionUtils.isEmpty(processedResourceTree)
                    ? null : JsonSerializeUtil.toJSONString(processedResourceTree));
            groupMenu.setTenantId(userContext.getTenantId());
            groupMenu.setCreatorId(userContext.getUserId());
            groupMenu.setCreator(userContext.getUserName());
            groupMenu.setYn(YnEnum.Y.getKey());
            groupMenus.add(groupMenu);
        }
        if (!CollectionUtils.isEmpty(groupMenus)) {
            sysGroupMenuService.saveBatch(groupMenus);
        }
    }

    public List<MenuNode> getMenuTreeByGroupId(Long groupId) {
        if (groupId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "用户组ID");
        }

        // 查询用户组绑定的菜单关系
        List<SysGroupMenu> groupMenus = sysGroupMenuService.list(Wrappers.<SysGroupMenu>lambdaQuery()
                .eq(SysGroupMenu::getGroupId, groupId)
                .eq(SysGroupMenu::getYn, YnEnum.Y.getKey()));
        // menuId -> 绑定记录（包含 menu_id = 0 的 root 绑定）
        Map<Long, SysGroupMenu> groupMenuMap = CollectionUtils.isEmpty(groupMenus)
                ? new HashMap<>()
                : groupMenus.stream()
                .filter(rm -> rm.getMenuId() != null)
                .collect(Collectors.toMap(SysGroupMenu::getMenuId, rm -> rm, (a, b) -> a));

        // 查询所有有效菜单
        List<SysMenu> allMenus = sysMenuDomainService.queryMenuList(null);
        if (CollectionUtils.isEmpty(allMenus)) {
            return new ArrayList<>();
        }
        // 先把 root 菜单（menuId = 0）加入到全量菜单中，让其参与自上而下、自下而上的打标逻辑
        boolean hasRootMenu = allMenus.stream()
                .anyMatch(menu -> menu.getId() != null && menu.getId() == 0L);
        if (!hasRootMenu) {
            SysMenu rootMenu = MenuBindResourceHelper.getRootMenu();
            allMenus.add(rootMenu);
        }

        // 查询所有有效资源（用于构建完整资源树）
        List<SysResource> allResources = sysResourceDomainService.queryResourceList(null);

        // 基于绑定记录 + 全量菜单/资源构建基础权限信息（含自上而下传播）
        List<MenuNode> menuNodes = MenuAuthHelper.buildMenuListWithAuth(
                groupMenus,
                SysGroupMenu::getMenuId,
                SysGroupMenu::getMenuBindType,
                SysGroupMenu::getResourceTreeJson,
                allMenus,
                allResources,
                sysMenuDomainService
        );

        // 填充菜单信息和资源详细信息
        Map<Long, SysMenu> allMenuMap = allMenus.stream().collect(Collectors.toMap(SysMenu::getId, m -> m));
        Map<Long, SysResource> allResourceMap = CollectionUtils.isEmpty(allResources)
                ? new HashMap<>()
                : allResources.stream().collect(Collectors.toMap(SysResource::getId, r -> r));

        List<MenuNode> filledMenuList = new ArrayList<>();
        if (!CollectionUtils.isEmpty(menuNodes)) {
            for (MenuNode menuNode : menuNodes) {
                Long menuId = menuNode.getId();
                if (menuId != null) {
                    SysMenu menu = allMenuMap.get(menuId);
                    if (menu == null) {
                        // 菜单已被删除，忽略
                        continue;
                    }

                    // 填充菜单基本信息
                    menuNode.setCode(menu.getCode());
                    menuNode.setName(menu.getName());
                    menuNode.setDescription(menu.getDescription());
                    menuNode.setSource(menu.getSource());
                    menuNode.setParentId(menu.getParentId());
                    menuNode.setPath(menu.getPath());
                    menuNode.setOpenType(menu.getOpenType());
                    menuNode.setIcon(menu.getIcon());
                    menuNode.setSortIndex(menu.getSortIndex());
                    menuNode.setStatus(menu.getStatus());
                }

                // 填充资源树中的资源详细信息
                if (CollectionUtils.isNotEmpty(menuNode.getResourceTree())) {
                    MenuBindResourceHelper.fillResourceTreeDetails(menuNode.getResourceTree(), allResourceMap);
                }

                filledMenuList.add(menuNode);
            }
        }

        if (CollectionUtils.isEmpty(filledMenuList)) {
            return new ArrayList<>();
        }

        // 构建菜单树
        MenuBindResourceHelper.buildMenuTree(filledMenuList);

        // 获取/构建 root 节点（menuId = 0）
        MenuNode rootNode = filledMenuList.stream()
                .filter(node -> node.getId() != null && node.getId() == 0L)
                .findFirst()
                .orElseGet(() -> {
                    SysMenu rootMenu = MenuBindResourceHelper.getRootMenu();
                    MenuNode r = new MenuNode();
                    BeanUtils.copyProperties(rootMenu, r);
                    r.setChildren(filledMenuList);
                    return r;
                });

        // 如果绑定关系中存在 menu_id = 0 的记录，则直接使用该记录的 menuBindType
        SysGroupMenu rootBinding = groupMenuMap.get(0L);
        if (rootBinding != null && rootBinding.getMenuBindType() != null) {
            rootNode.setMenuBindType(rootBinding.getMenuBindType());
        }

        // 自下而上打标 menuBindType（仅对“未显式绑定”的菜单和 root 进行合成打标）
        Set<Long> explicitlyBoundMenuIds = CollectionUtils.isEmpty(groupMenus)
                ? new HashSet<>()
                : groupMenus.stream()
                .filter(rm -> rm.getMenuId() != null)
                .map(SysGroupMenu::getMenuId)
                .collect(Collectors.toSet());

        MenuBindResourceHelper.adjustMenuBindTypeBottomUp(rootNode, explicitlyBoundMenuIds);

        // 如果 root 的 menuBindType 为 ALL 或 NONE，则按规则将该类型自上而下强制传播到所有子菜单
        MenuBindResourceHelper.propagateRootMenuBindType(rootNode);

        List<MenuNode> result = new ArrayList<>();
        result.add(rootNode);
        return result;
    }
    

}


