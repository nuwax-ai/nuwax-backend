package com.xspaceagi.system.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.system.domain.model.MenuNode;
import com.xspaceagi.system.domain.model.ResourceNode;
import com.xspaceagi.system.domain.model.RoleBindMenuModel;
import com.xspaceagi.system.domain.model.SortIndex;
import com.xspaceagi.system.domain.service.SysMenuDomainService;
import com.xspaceagi.system.domain.service.SysResourceDomainService;
import com.xspaceagi.system.domain.service.SysRoleDomainService;
import com.xspaceagi.system.domain.service.SysSubjectPermissionDomainService;
import com.xspaceagi.system.infra.dao.entity.*;
import com.xspaceagi.system.infra.dao.service.*;
import com.xspaceagi.system.sdk.service.dto.TokenLimit;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.*;
import com.xspaceagi.system.spec.constants.PermissionConstants;
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
 * 系统角色领域服务实现
 */
@Service
public class SysRoleDomainServiceImpl implements SysRoleDomainService {

    @Resource
    private UserService userService;
    @Resource
    private SysRoleService sysRoleService;
    @Resource
    private SysUserRoleService sysUserRoleService;
    @Resource
    private SysRoleMenuService sysRoleMenuService;
    @Resource
    private SysMenuService sysMenuService;
    @Resource
    private SysResourceService sysResourceService;
    @Resource
    private SysMenuDomainService sysMenuDomainService;
    @Resource
    private SysResourceDomainService sysResourceDomainService;
    @Resource
    private SysDataPermissionDomainServiceImpl sysDataPermissionDomainService;
    @Resource
    private SysSubjectPermissionDomainService sysSubjectPermissionDomainService;
    @Resource
    private MenuBindResourceHelper menuBindResourceHelper;

    private void normalizeRole(SysRole role) {
        role.setCode(StringUtils.trim(role.getCode()));
        role.setName(StringUtils.trim(role.getName()));
        role.setDescription(StringUtils.trim(role.getDescription()));

        if (StringUtils.isNotBlank(role.getCode()) && !role.getCode().matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacCodeFormatInvalid);
        }
        if (StringUtils.length(role.getCode()) > 100) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacCodeLengthExceeded);
        }
        if (StringUtils.length(role.getName()) > 50) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacNameLengthExceeded);
        }
        if (StringUtils.length(role.getDescription()) > 500) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacDescLengthExceeded);
        }
    }

    @Override
    public void addRole(SysRole role, UserContext userContext) {
        if (StringUtils.isBlank(role.getName())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "名称");
        }

        // 如果编码为空，根据名称自动生成编码
        if (StringUtils.isBlank(role.getCode())) {
            String generatedCode = CodeGeneratorUtil.generateUniqueCodeFromName(
                role.getName(),
                    "role_",
                code -> queryRoleByCode(code) != null
            );
            role.setCode(generatedCode);
        }

        normalizeRole(role);

        SysRole exist = queryRoleByCode(role.getCode());
        if (exist != null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRoleCodeDuplicate);
        }

        if (role.getSource() == null) {
            role.setSource(SourceEnum.CUSTOM.getCode());
        }
        if (role.getStatus() == null) {
            role.setStatus(StatusEnum.ENABLED.getCode());
        }
        if (role.getSortIndex() == null) {
            role.setSortIndex(0);
        }
        role.setTenantId(userContext.getTenantId());
        role.setCreatorId(userContext.getUserId());
        role.setCreator(userContext.getUserName());
        role.setYn(YnEnum.Y.getKey());
        sysRoleService.save(role);

        if (role.getId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacSaveFailed);
        }
    }

    @Override
    public boolean updateRole(SysRole role, UserContext userContext) {
        if (role.getId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "ID");
        }
        normalizeRole(role);

        SysRole exist = queryRoleById(role.getId());
        if (exist == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRoleNotFound);
        }
        if (SourceEnum.SYSTEM.getCode().equals(exist.getSource())) {
            if (role.getCode() != null && !role.getCode().equals(exist.getCode())) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemBuiltinRoleCodeImmutable);
            }
            if (role.getName() != null && !role.getName().equals(exist.getName())) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemBuiltinRoleNameImmutable);
            }
            if (role.getStatus() != null && !role.getStatus().equals(StatusEnum.ENABLED.getCode())) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemBuiltinRoleCannotDisable);
            }
        }

        boolean statusChanged = role.getStatus() != null
                && !Objects.equals(exist.getStatus(), role.getStatus());

        role.setModifierId(userContext.getUserId());
        role.setModifier(userContext.getUserName());
        sysRoleService.updateById(role);
        return statusChanged;
    }

    @Override
    public void bindDataPermission(Long roleId, SysDataPermission dataPermission, UserContext userContext) {
        if (roleId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "角色ID");
        }
        SysRole exist = queryRoleById(roleId);
        if (exist == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRoleNotFound);
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

        dataPermission.setTargetType(PermissionTargetTypeEnum.ROLE.getCode());
        dataPermission.setTargetId(roleId);
        SysDataPermission oldDataPermission = sysDataPermissionDomainService.getByTarget(PermissionTargetTypeEnum.ROLE, roleId);
        if (oldDataPermission != null) {
            sysDataPermissionDomainService.update(oldDataPermission.getId(), dataPermission, userContext);
        } else {
            sysDataPermissionDomainService.add(dataPermission, userContext);
        }

        // 全量覆盖写入
        sysSubjectPermissionDomainService.replaceSubjectsByTargetAndSubjectIds(PermissionTargetTypeEnum.ROLE, roleId,
                PermissionSubjectTypeEnum.MODEL, dataPermission.getModelIds(), userContext);
        sysSubjectPermissionDomainService.replaceSubjectsByTargetAndSubjectIds(PermissionTargetTypeEnum.ROLE, roleId,
                PermissionSubjectTypeEnum.AGENT, dataPermission.getAgentIds(), userContext);
        sysSubjectPermissionDomainService.replaceSubjectsByTargetAndSubjectIds(PermissionTargetTypeEnum.ROLE, roleId,
                PermissionSubjectTypeEnum.PAGE, dataPermission.getPageAgentIds(), userContext);
        sysSubjectPermissionDomainService.replaceSubjectsByTargetAndSubjectKeyConfig(PermissionTargetTypeEnum.ROLE, roleId,
                PermissionSubjectTypeEnum.OPEN_API, dataPermission.getOpenApiConfigMap(), userContext);
        sysSubjectPermissionDomainService.replaceSubjectsByTargetAndSubjectIds(PermissionTargetTypeEnum.ROLE, roleId,
                PermissionSubjectTypeEnum.KNOWLEDGE, dataPermission.getKnowledgeIds(), userContext);
    }

    @Override
    public void batchUpdateRoleSort(List<SortIndex> sortIndexList, UserContext userContext) {
        if (CollectionUtils.isEmpty(sortIndexList)) {
            return;
        }
        for (SortIndex item : sortIndexList) {
            if (item == null || item.getId() == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "角色ID");
            }
            SysRole exist = queryRoleById(item.getId());
            if (exist == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRoleNotFoundWithRowId, item.getId());
            }
            if (item.getSortIndex() == null) {
                continue;
            }
            SysRole updateRole = new SysRole();
            updateRole.setId(item.getId());
            updateRole.setSortIndex(item.getSortIndex());
            updateRole.setModifierId(userContext.getUserId());
            updateRole.setModifier(userContext.getUserName());
            sysRoleService.updateById(updateRole);
        }
    }

    @Override
    public void deleteRole(Long roleId, UserContext userContext) {
        if (roleId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "ID");
        }
        SysRole exist = queryRoleById(roleId);
        if (exist == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRoleNotFound);
        }

        if (SourceEnum.SYSTEM.getCode().equals(exist.getSource())) {
            //throw new BizException("系统内置角色不能删除");
        }

        // 删除角色
        sysRoleService.removeById(roleId);
        // 删除数据权限
        sysDataPermissionDomainService.deleteByTaret(PermissionTargetTypeEnum.ROLE, roleId, userContext);
        // 删除角色绑定的用户
        sysUserRoleService.remove(Wrappers.<SysUserRole>lambdaUpdate().eq(SysUserRole::getRoleId, roleId));
        // 删除角色绑定的菜单
        sysRoleMenuService.remove(Wrappers.<SysRoleMenu>lambdaUpdate().eq(SysRoleMenu::getRoleId, roleId));
    }

    @Override
    public SysRole queryRoleById(Long roleId) {
        LambdaQueryWrapper<SysRole> wrapper = Wrappers.<SysRole>lambdaQuery().eq(SysRole::getId, roleId).eq(SysRole::getYn, YnEnum.Y.getKey());
        return sysRoleService.getOne(wrapper);
    }

    @Override
    public List<SysRole> queryRoleListByIds(List<Long> roleIds) {
        if (CollectionUtils.isEmpty(roleIds)) {
            return Collections.emptyList();
        }
        List<Long> ids = roleIds.stream().filter(Objects::nonNull).distinct().toList();
        return sysRoleService.list(Wrappers.<SysRole>lambdaQuery()
                .in(SysRole::getId, ids)
                .eq(SysRole::getYn, YnEnum.Y.getKey()));
    }

    @Override
    public SysRole queryRoleByCode(String roleCode) {
        LambdaQueryWrapper<SysRole> wrapper = Wrappers.<SysRole>lambdaQuery().eq(SysRole::getCode, roleCode).eq(SysRole::getYn, YnEnum.Y.getKey());
        return sysRoleService.getOne(wrapper);
    }

    @Override
    public List<SysRole> queryRoleList(SysRole role) {
        LambdaQueryWrapper<SysRole> wrapper = Wrappers.<SysRole>lambdaQuery()
                .eq(StringUtils.isNotBlank(role.getCode()), SysRole::getCode, role.getCode())
                .like(StringUtils.isNotBlank(role.getName()), SysRole::getName, role.getName())
                .eq(Objects.nonNull(role.getSource()), SysRole::getSource, role.getSource())
                .eq(Objects.nonNull(role.getStatus()), SysRole::getStatus, role.getStatus())
                .eq(SysRole::getYn, YnEnum.Y.getKey()).orderByAsc(SysRole::getSortIndex);
        return sysRoleService.list(wrapper);
    }

    @Override
    public List<User> getUserListByRoleId(Long roleId) {
        LambdaQueryWrapper<SysUserRole> wrapper = Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getRoleId, roleId).eq(SysUserRole::getYn, YnEnum.Y.getKey());
        List<SysUserRole> list = sysUserRoleService.list(wrapper);
        if (CollectionUtils.isEmpty(list)) {
            return new ArrayList<>();
        }
        List<Long> userIdList = list.stream().map(SysUserRole::getUserId).toList();
        List<User> users = userService.listByIds(userIdList);
        return users;
    }

    @Override
    public IPage<User> getUserPageByRoleId(Long roleId, String userName, long pageNo, long pageSize) {
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
        LambdaQueryWrapper<SysUserRole> wrapper = Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getRoleId, roleId)
                .eq(SysUserRole::getYn, YnEnum.Y.getKey())
                .in(CollectionUtils.isNotEmpty(matchingUserIds), SysUserRole::getUserId, matchingUserIds)
                .orderByAsc(SysUserRole::getUserId);
        IPage<SysUserRole> userRolePage = sysUserRoleService.page(new Page<>(pageNo, pageSize), wrapper);
        if (CollectionUtils.isEmpty(userRolePage.getRecords())) {
            return new Page<>(pageNo, pageSize, userRolePage.getTotal());
        }
        List<Long> pageUserIds = userRolePage.getRecords().stream().map(SysUserRole::getUserId).filter(Objects::nonNull).toList();
        List<User> users = userService.listByIds(pageUserIds);
        Map<Long, User> userMap = users.stream().collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        List<User> orderedUsers = pageUserIds.stream().map(userMap::get).filter(Objects::nonNull).toList();
        Page<User> page = new Page<>(pageNo, pageSize, userRolePage.getTotal());
        page.setRecords(orderedUsers);
        return page;
    }

    @Override
    public long countUsersByRoleId(Long roleId) {
        LambdaQueryWrapper<SysUserRole> wrapper = Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getRoleId, roleId)
                .eq(SysUserRole::getYn, YnEnum.Y.getKey());
        return sysUserRoleService.count(wrapper);
    }

    @Override
    public List<Long> getUserIdsByRoleId(Long roleId) {
        LambdaQueryWrapper<SysUserRole> wrapper = Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getRoleId, roleId)
                .eq(SysUserRole::getYn, YnEnum.Y.getKey())
                .select(SysUserRole::getUserId);
        List<SysUserRole> list = sysUserRoleService.list(wrapper);
        if (CollectionUtils.isEmpty(list)) {
            return new ArrayList<>();
        }
        return list.stream().map(SysUserRole::getUserId).filter(Objects::nonNull).toList();
    }

    @Override
    public List<SysRole> queryRoleListByUserId(Long userId) {
        // 先查询用户角色关联
        LambdaQueryWrapper<SysUserRole> wrapper = Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getUserId, userId)
                .eq(SysUserRole::getYn, YnEnum.Y.getKey());
        List<SysUserRole> userRoles = sysUserRoleService.list(wrapper);

        if (CollectionUtils.isEmpty(userRoles)) {
            return new ArrayList<>();
        }

        // 获取角色ID列表
        List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).collect(Collectors.toList());

        // 查询角色信息
        LambdaQueryWrapper<SysRole> roleWrapper = new LambdaQueryWrapper<>();
        roleWrapper.in(SysRole::getId, roleIds)
                .eq(SysRole::getYn, YnEnum.Y.getKey())
                .orderByAsc(SysRole::getSortIndex);
        return sysRoleService.list(roleWrapper);
    }

    @Override
    public void roleBindUser(Long roleId, List<Long> userIds, UserContext userContext) {
        if (roleId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "角色ID");
        }
        SysRole sysRole = queryRoleById(roleId);
        if (sysRole == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRoleNotFoundWithRoleId, roleId);
        }

        // 校验用户是否存在且为管理员类型
        if (CollectionUtils.isNotEmpty(userIds)) {
            Set<Long> distinctUserIds = new HashSet<>(userIds);
            List<User> users = userService.listByIds(distinctUserIds);
            List<Long> existUserIds = users.stream().map(User::getId).toList();

            userIds.forEach(userId -> {
                if (!existUserIds.contains(userId)) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacUserNotFoundWithId, userId);
                }
            });
            users.forEach(user -> {
                if (user.getRole() == null || !User.Role.Admin.equals(user.getRole())) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRoleBindRequiresAdminUser,
                            user.getUserName());
                }
            });
        }

        // 物理删除原绑定关系
        LambdaUpdateWrapper<SysUserRole> wrapper = Wrappers.<SysUserRole>lambdaUpdate().eq(SysUserRole::getRoleId, roleId);
        sysUserRoleService.remove(wrapper);

        if (CollectionUtils.isEmpty(userIds)) {
            return;
        }

        List<SysUserRole> userRoleList = userIds.stream().map(userId -> {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            userRole.setTenantId(userContext.getTenantId());
            userRole.setCreatorId(userContext.getUserId());
            userRole.setCreator(userContext.getUserName());
            userRole.setYn(YnEnum.Y.getKey());
            return userRole;
        }).toList();

        if (!CollectionUtils.isEmpty(userRoleList)) {
            sysUserRoleService.saveBatch(userRoleList);
        }
    }

    @Override
    public void roleAddUser(Long roleId, Long userId, UserContext userContext) {
        if (roleId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "角色ID");
        }
        if (userId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "用户ID");
        }
        SysRole sysRole = queryRoleById(roleId);
        if (sysRole == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRoleNotFoundWithRoleId, roleId);
        }
        User user = userService.getById(userId);
        if (user == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacUserNotFoundWithId, userId);
        }
        if (user.getRole() == null || !User.Role.Admin.equals(user.getRole())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRoleBindRequiresAdminUser,
                    user.getUserName());
        }
        // 检查是否已绑定，避免重复插入
        LambdaQueryWrapper<SysUserRole> queryWrapper = Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getRoleId, roleId)
                .eq(SysUserRole::getUserId, userId)
                .eq(SysUserRole::getYn, YnEnum.Y.getKey());
        if (sysUserRoleService.count(queryWrapper) > 0) {
            return;
        }
        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        userRole.setTenantId(userContext.getTenantId());
        userRole.setCreatorId(userContext.getUserId());
        userRole.setCreator(userContext.getUserName());
        userRole.setYn(YnEnum.Y.getKey());
        sysUserRoleService.save(userRole);
    }

    @Override
    public void roleRemoveUser(Long roleId, Long userId, UserContext userContext) {
        if (roleId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "角色ID");
        }
        if (userId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "用户ID");
        }
        LambdaUpdateWrapper<SysUserRole> wrapper = Wrappers.<SysUserRole>lambdaUpdate()
                .eq(SysUserRole::getRoleId, roleId)
                .eq(SysUserRole::getUserId, userId);
        sysUserRoleService.remove(wrapper);
    }

    @Override
    public void userBindRole(Long userId, List<Long> roleIds, UserContext userContext) {
        if (userId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "用户ID");
        }
        User user = userService.getById(userId);
        if (user == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRbacUserNotFoundWithId, userId);
        }
        if ((user.getRole() == null || !User.Role.Admin.equals(user.getRole())) && CollectionUtils.isNotEmpty(roleIds)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRoleBindRequiresAdminUser,
                    user.getUserName());
        }

        // 校验角色是否存在
        if (CollectionUtils.isNotEmpty(roleIds)) {
            Set<Long> distinctRoleIds = new HashSet<>(roleIds);
            List<SysRole> roles = sysRoleService.listByIds(distinctRoleIds);
            if (CollectionUtils.isEmpty(roles)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRoleNotFound);
            }
            Set<Long> existRoleIds = roles.stream().map(SysRole::getId).collect(Collectors.toSet());
            roleIds.forEach(roleId -> {
                if (!existRoleIds.contains(roleId)) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRoleNotFoundWithRoleId, roleId);
                }
            });
        }

        // 物理删除原绑定关系
        LambdaUpdateWrapper<SysUserRole> wrapper = Wrappers.<SysUserRole>lambdaUpdate().eq(SysUserRole::getUserId, userId);
        sysUserRoleService.remove(wrapper);

        if (CollectionUtils.isEmpty(roleIds)) {
            return;
        }

        List<SysUserRole> userRoleList = roleIds.stream().map(roleId -> {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            userRole.setTenantId(userContext.getTenantId());
            userRole.setCreatorId(userContext.getUserId());
            userRole.setCreator(userContext.getUserName());
            userRole.setYn(YnEnum.Y.getKey());
            return userRole;
        }).toList();

        if (!CollectionUtils.isEmpty(userRoleList)) {
            sysUserRoleService.saveBatch(userRoleList);
        }
    }

    @Override
    public void bindMenu(RoleBindMenuModel model, UserContext userContext) {
        if (model == null || model.getRoleId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "角色ID");
        }

        Long roleId = model.getRoleId();
        SysRole role = sysRoleService.getById(roleId);
        if (role == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemRoleNotFound);
        }

        List<MenuNode> menuBindResourceList = model.getMenuBindResourceList();
        if (CollectionUtils.isEmpty(menuBindResourceList)) {
            if (RoleEnum.SUPER_ADMIN.getCode().equals(role.getCode())) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemSuperAdminRequiresRoleMenuResources);
            }
            LambdaUpdateWrapper<SysRoleMenu> deleteWrapper = Wrappers.<SysRoleMenu>lambdaUpdate().eq(SysRoleMenu::getRoleId, roleId);
            sysRoleMenuService.remove(deleteWrapper);
            return;
        }

        if (RoleEnum.SUPER_ADMIN.getCode().equals(role.getCode())) {
            validateSuperAdminNecessaryPermissions(menuBindResourceList);
        }

        LambdaUpdateWrapper<SysRoleMenu> deleteWrapper = Wrappers.<SysRoleMenu>lambdaUpdate().eq(SysRoleMenu::getRoleId, roleId);
        sysRoleMenuService.remove(deleteWrapper);

        List<SysMenu> allMenus = sysMenuDomainService.queryMenuList(null);
        List<SysResource> allResources = sysResourceDomainService.queryResourceList(null);
        MenuBindResourceHelper.MenuResourceMaps maps = menuBindResourceHelper.buildMenuAndResourceMaps(allMenus, allResources);
        Map<Long, Integer> menuBindTypeMap = menuBindResourceHelper.buildAndPropagateMenuBindTypeMap(menuBindResourceList, maps.menuChildrenMap);

        // menuBindType=1(ALL) 表示该节点下所有子菜单均被绑定，但每个子菜单的资源绑定可能不同，需存每个子菜单并各自存 resource_tree_json
        List<SysRoleMenu> roleMenus = new ArrayList<>();
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
            SysRoleMenu roleMenu = new SysRoleMenu();
            roleMenu.setRoleId(roleId);
            roleMenu.setMenuId(menuId);
            roleMenu.setMenuBindType(menuBindType);
            List<ResourceNode> processedResourceTree = menuBindResourceHelper.processResourceTreeForMenu(
                    menuId, menuNode.getResourceTree(), maps.resourceMap, maps.resourceChildrenMap);
            roleMenu.setResourceTreeJson(CollectionUtils.isEmpty(processedResourceTree)
                    ? null : JsonSerializeUtil.toJSONString(processedResourceTree));
            roleMenu.setTenantId(userContext.getTenantId());
            roleMenu.setCreatorId(userContext.getUserId());
            roleMenu.setCreator(userContext.getUserName());
            roleMenu.setYn(YnEnum.Y.getKey());
            roleMenus.add(roleMenu);
        }
        if (!CollectionUtils.isEmpty(roleMenus)) {
            sysRoleMenuService.saveBatch(roleMenus);
        }
    }

    /**
     * 超级管理员角色绑定菜单时，必须包含必要权限，否则抛异常。
     */
    private void validateSuperAdminNecessaryPermissions(List<MenuNode> menuBindResourceList) {
        List<SysMenu> allMenus = sysMenuDomainService.queryMenuList(null);
        List<SysResource> allResources = sysResourceDomainService.queryResourceList(null);
        if (CollectionUtils.isEmpty(allMenus) || CollectionUtils.isEmpty(allResources)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemSuperAdminRequiresRoleMenuResourcesAlt);
        }
        Map<String, SysMenu> menuCodeMap = allMenus.stream()
                .filter(m -> StringUtils.isNotBlank(m.getCode()))
                .collect(Collectors.toMap(SysMenu::getCode, m -> m, (a, b) -> a));
        Map<String, SysResource> allResourceCodeMap = allResources.stream()
                .filter(r -> StringUtils.isNotBlank(r.getCode()))
                .collect(Collectors.toMap(SysResource::getCode, r -> r, (a, b) -> a));
        MenuBindResourceHelper.MenuResourceMaps maps = menuBindResourceHelper.buildMenuAndResourceMaps(allMenus, allResources);
        Map<Long, Integer> menuBindTypeMap = menuBindResourceHelper.buildAndPropagateMenuBindTypeMap(menuBindResourceList, maps.menuChildrenMap);
        Map<Long, MenuNode> bindMap = menuBindResourceList.stream()
                .filter(n -> n.getId() != null)
                .collect(Collectors.toMap(MenuNode::getId, n -> n, (a, b) -> a));

        List<String> necessaryMenuCodes = new ArrayList<>(PermissionConstants.superAdminNecessaryMenus);
        for (String menuCode : necessaryMenuCodes) {
            SysMenu menu = menuCodeMap.get(menuCode);
            if (menu == null || menu.getId() == null) {
                continue;
            }
            Integer bindType = menuBindTypeMap.get(menu.getId());
            if (bindType == null || BindTypeEnum.NONE.getCode().equals(bindType)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemSuperAdminRequiresMenuByName,
                        menu.getName());
            }
        }

        Set<String> necessaryResourceCodes = new HashSet<>(PermissionConstants.superAdminNecessaryResources);
        Set<Long> necessaryMenuIds = necessaryMenuCodes.stream()
                .map(menuCodeMap::get)
                .filter(Objects::nonNull)
                .map(SysMenu::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<SysMenuResource> allMenuResources = sysMenuDomainService.queryResourceListByMenuIds(necessaryMenuIds);
        Map<Long, Long> resourceIdToMenuId = new HashMap<>();
        for (SysMenuResource mr : allMenuResources) {
            if (mr.getResourceId() == null || mr.getMenuId() == null) {
                continue;
            }
            resourceIdToMenuId.put(mr.getResourceId(), mr.getMenuId());
            // resourceBindType=1(ALL) 时，子资源也视为该菜单下已授权，需纳入映射
            if (BindTypeEnum.ALL.getCode().equals(mr.getResourceBindType())) {
                Set<Long> descendantIds = new HashSet<>();
                MenuBindResourceHelper.collectChildrenResourceIds(mr.getResourceId(), maps.resourceChildrenMap, descendantIds);
                for (Long childId : descendantIds) {
                    resourceIdToMenuId.putIfAbsent(childId, mr.getMenuId());
                }
            }
        }

        for (String resourceCode : necessaryResourceCodes) {
            SysResource resource = allResourceCodeMap.get(resourceCode);
            if (resource == null || resource.getId() == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemSuperAdminResourceCodeMissing,
                        resourceCode);
            }
            Long menuId = resourceIdToMenuId.get(resource.getId());
            if (menuId == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemSuperAdminResourceMenuOrphan,
                        resource.getName());
            }
            MenuNode menuNode = bindMap.get(menuId);
            if (menuNode == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemSuperAdminBindIncomplete);
            }

            List<ResourceNode> flatResources = menuNode.getFlattenResourceList();
            boolean hasValidBind = flatResources.stream()
                    .anyMatch(r -> resource.getId().equals(r.getId())
                            && r.getResourceBindType() != null
                            && !BindTypeEnum.NONE.getCode().equals(r.getResourceBindType()));
            if (!hasValidBind) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemSuperAdminBindIncomplete);
            }
        }
    }

    public List<MenuNode> getMenuTreeByRoleId(Long roleId) {
        if (roleId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "角色ID");
        }

        // 查询角色绑定的菜单关系
        List<SysRoleMenu> roleMenus = sysRoleMenuService.list(Wrappers.<SysRoleMenu>lambdaQuery()
                .eq(SysRoleMenu::getRoleId, roleId)
                .eq(SysRoleMenu::getYn, YnEnum.Y.getKey()));
        // menuId -> 绑定记录（包含 menu_id = 0 的 root 绑定）
        Map<Long, SysRoleMenu> roleMenuMap = CollectionUtils.isEmpty(roleMenus)
                ? new HashMap<>()
                : roleMenus.stream()
                    .filter(rm -> rm.getMenuId() != null)
                    .collect(Collectors.toMap(SysRoleMenu::getMenuId, rm -> rm, (a, b) -> a));

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
                roleMenus,
                SysRoleMenu::getMenuId,
                SysRoleMenu::getMenuBindType,
                SysRoleMenu::getResourceTreeJson,
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
                    menuNode.setParentId(menu.getParentId());
                    menuNode.setCode(menu.getCode());
                    menuNode.setName(menu.getName());
                    menuNode.setDescription(menu.getDescription());
                    menuNode.setSource(menu.getSource());
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
        SysRoleMenu rootBinding = roleMenuMap.get(0L);
        if (rootBinding != null && rootBinding.getMenuBindType() != null) {
            rootNode.setMenuBindType(rootBinding.getMenuBindType());
        }

        // 自下而上打标 menuBindType（仅对“未显式绑定”的菜单和 root 进行合成打标）
        Set<Long> explicitlyBoundMenuIds = CollectionUtils.isEmpty(roleMenus)
                ? new HashSet<>()
                : roleMenus.stream()
                        .filter(rm -> rm.getMenuId() != null)
                        .map(SysRoleMenu::getMenuId)
                        .collect(Collectors.toSet());

        MenuBindResourceHelper.adjustMenuBindTypeBottomUp(rootNode, explicitlyBoundMenuIds);

        // 如果 root 的 menuBindType 为 ALL 或 NONE，则按规则将该类型自上而下强制传播到所有子菜单
        MenuBindResourceHelper.propagateRootMenuBindType(rootNode);

        List<MenuNode> result = new ArrayList<>();
        result.add(rootNode);
        return result;
    }

}

