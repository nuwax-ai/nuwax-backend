package com.xspaceagi.system.web.controller.permission;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.system.application.converter.MenuNodeConverter;
import com.xspaceagi.system.application.converter.RoleBindMenuModelConverter;
import com.xspaceagi.system.application.converter.SysDataPermissionConverter;
import com.xspaceagi.system.application.dto.permission.*;
import com.xspaceagi.system.application.service.SysDataPermissionApplicationService;
import com.xspaceagi.system.application.service.SysRoleApplicationService;
import com.xspaceagi.system.application.service.SysSubjectPermissionApplicationService;
import com.xspaceagi.system.domain.model.MenuNode;
import com.xspaceagi.system.domain.model.RoleBindMenuModel;
import com.xspaceagi.system.domain.model.SortIndex;
import com.xspaceagi.system.infra.dao.entity.SysDataPermission;
import com.xspaceagi.system.infra.dao.entity.SysRole;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.PermissionSubjectTypeEnum;
import com.xspaceagi.system.spec.enums.PermissionTargetTypeEnum;
import com.xspaceagi.system.spec.enums.SourceEnum;
import com.xspaceagi.system.spec.enums.StatusEnum;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.xspaceagi.system.spec.utils.I18nUtil;
import com.xspaceagi.system.web.controller.base.BaseController;
import com.fasterxml.jackson.core.type.TypeReference;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import com.xspaceagi.system.spec.dto.PageQueryVo;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Slf4j
@Tag(name = "权限管理-角色", description = "角色相关接口")
@RestController
@RequestMapping("/api/system/role")
public class SysRoleController extends BaseController {

    @Resource
    private SysRoleApplicationService sysRoleApplicationService;
    @Autowired
    private SysDataPermissionApplicationService sysDataPermissionApplicationService;
    @Autowired
    private SysSubjectPermissionApplicationService sysSubjectPermissionApplicationService;

    @RequireResource(ROLE_MANAGE_ADD)
    @Operation(summary = "添加角色")
    @PostMapping(value = "/add", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> addRole(@RequestBody SysRoleAddDto dto) {
        if (dto == null) {
            return ReqResult.error("参数不能为空");
        }
        if (dto.getSource() != null && SourceEnum.isInValid(dto.getSource())) {
            return ReqResult.error("参数source错误");
        }
        if (dto.getStatus() != null && StatusEnum.isInValid(dto.getStatus())) {
            return ReqResult.error("参数status错误");
        }

        SysRole role = new SysRole();
        BeanUtils.copyProperties(dto, role);
        sysRoleApplicationService.addRole(role, getUser());
        return ReqResult.success();
    }

    @RequireResource(ROLE_MANAGE_MODIFY)
    @Operation(summary = "更新角色")
    @PostMapping("/update")
    public ReqResult<Void> updateRole(@RequestBody SysRoleUpdateDto dto) {
        if (dto == null) {
            return ReqResult.error("参数不能为空");
        }
        if (dto.getSource() != null && SourceEnum.isInValid(dto.getSource())) {
            return ReqResult.error("参数source错误");
        }
        if (dto.getStatus() != null && StatusEnum.isInValid(dto.getStatus())) {
            return ReqResult.error("参数status错误");
        }

        SysRole role = new SysRole();
        BeanUtils.copyProperties(dto, role);
        role.setCode(null); // code不允许更新
        sysRoleApplicationService.updateRole(role, getUser());
        return ReqResult.success();
    }

    @RequireResource(ROLE_MANAGE_DELETE)
    @Operation(summary = "删除角色")
    @PostMapping("/delete/{roleId}")
    public ReqResult<Void> deleteRole(@PathVariable Long roleId) {
        if (roleId == null) {
            return ReqResult.error("参数不能为空");
        }

        sysRoleApplicationService.deleteRole(roleId, getUser());
        return ReqResult.success();
    }

    @RequireResource(ROLE_MANAGE_QUERY)
    @Operation(summary = "根据ID查询角色")
    @GetMapping("/{roleId}")
    public ReqResult<SysRoleDto> getRoleById(@PathVariable Long roleId) {
        if (roleId == null) {
            return ReqResult.error("参数不能为空");
        }

        SysRole role = sysRoleApplicationService.getRoleById(roleId);
        if (role == null) {
            return ReqResult.error("角色不存在");
        }
        SysRoleDto roleDto = new SysRoleDto();
        BeanUtils.copyProperties(role, roleDto);

//        SysDataPermission dataPermission = sysDataPermissionApplicationService.getByTarget(PermissionTargetTypeEnum.ROLE, role.getId());
//        if (dataPermission != null) {
//            roleDto.setModelIds(dataPermission.getModelIds());
//            roleDto.setTokenLimit(dataPermission.getTokenLimit());
//        }
        I18nUtil.replaceSystemMessage(roleDto);
        return ReqResult.success(roleDto);
    }

    @RequireResource(ROLE_MANAGE_QUERY)
    @Operation(summary = "根据编码查询角色")
    @GetMapping("/code/{roleCode}")
    public ReqResult<SysRoleDto> getRoleByCode(@PathVariable String roleCode) {
        if (StringUtils.isBlank(roleCode)) {
            return ReqResult.error("参数不能为空");
        }

        SysRole role = sysRoleApplicationService.getRoleByCode(roleCode);
        if (role == null) {
            return ReqResult.error("角色不存在");
        }
        SysRoleDto roleDto = new SysRoleDto();
        BeanUtils.copyProperties(role, roleDto);

//        SysDataPermission dataPermission = sysDataPermissionApplicationService.getByTarget(PermissionTargetTypeEnum.ROLE, role.getId());
//        if (dataPermission != null) {
//            roleDto.setModelIds(dataPermission.getModelIds());
//            roleDto.setTokenLimit(dataPermission.getTokenLimit());
//        }
        I18nUtil.replaceSystemMessage(roleDto);
        return ReqResult.success(roleDto);
    }

    @RequireResource(ROLE_MANAGE_MODIFY)
    @Operation(summary = "调整角色顺序")
    @PostMapping("/update-sort")
    public ReqResult<Void> updateSortIndex(@RequestBody SortIndexUpdateDto dto) {
        if (dto == null || CollectionUtils.isEmpty(dto.getItems())) {
            return ReqResult.error("参数不能为空");
        }
        List<SortIndex> sortIndexList = dto.getItems().stream()
                .map(item -> {
                    SortIndex model = new SortIndex();
                    model.setId(item.getId());
                    model.setParentId(item.getParentId());
                    model.setSortIndex(item.getSortIndex());
                    return model;
                })
                .collect(Collectors.toList());
        sysRoleApplicationService.batchUpdateRoleSort(sortIndexList, getUser());
        return ReqResult.success();
    }

    @RequireResource(ROLE_MANAGE_QUERY)
    @Operation(summary = "根据条件查询角色")
    @GetMapping("/list")
    public ReqResult<List<SysRoleDto>> getRoleList(SysRoleQueryDto dto) {
        SysRole sysRole = new SysRole();
        if (dto != null) {
            BeanUtils.copyProperties(dto, sysRole);
        }

        List<SysRole> roleList = sysRoleApplicationService.getRoleList(sysRole);
        if (CollectionUtils.isEmpty(roleList)) {
            return ReqResult.success();
        }
//        List<Long> roleIds = roleList.stream().map(SysRole::getId).toList();
//        List<SysDataPermission> dataPermissionList = sysDataPermissionApplicationService.getByTargetList(PermissionTargetTypeEnum.ROLE, roleIds);
//        Map<Long, SysDataPermission> permissionMap = dataPermissionList.stream()
//                .collect(Collectors.toMap(SysDataPermission::getTargetId, p -> p, (a, b) -> a));

        List<SysRoleDto> dtoList = roleList.stream().map(r -> {
            SysRoleDto roleDto = new SysRoleDto();
            BeanUtils.copyProperties(r, roleDto);
//            SysDataPermission dataPermission = permissionMap.get(r.getId());
//            if (dataPermission != null) {
//                roleDto.setModelIds(dataPermission.getModelIds());
//                roleDto.setTokenLimit(dataPermission.getTokenLimit());
//            }
            return roleDto;
        }).collect(Collectors.toList());

        I18nUtil.replaceSystemMessage(dtoList);
        return ReqResult.success(dtoList);
    }

    @RequireResource(ROLE_MANAGE_BIND_USER)
    @Operation(summary = "分页查询角色已绑定的用户，支持按userName模糊筛选")
    @PostMapping("/list-user")
    public ReqResult<IPage<SysUserDto>> getUserListByRoleId(@RequestBody PageQueryVo<SysRoleUserQueryDto> pageQueryVo) {
        if (pageQueryVo == null || pageQueryVo.getQueryFilter() == null || pageQueryVo.getQueryFilter().getRoleId() == null) {
            return ReqResult.error("角色ID不能为空");
        }
        SysRoleUserQueryDto queryDto = pageQueryVo.getQueryFilter();
        Long roleId = queryDto.getRoleId();
        String userName = queryDto.getUserName();
        long pageNo = pageQueryVo.getPageNo() != null ? pageQueryVo.getPageNo() : 1L;
        long pageSize = pageQueryVo.getPageSize() != null ? pageQueryVo.getPageSize() : 10L;

        IPage<User> userPage = sysRoleApplicationService.getUserPageByRoleId(roleId, userName, pageNo, pageSize);
        List<SysUserDto> dtoList = userPage.getRecords().stream().map(user -> {
            SysUserDto userDto = new SysUserDto();
            BeanUtils.copyProperties(user, userDto);
            userDto.setUserId(user.getId());
            return userDto;
        }).collect(Collectors.toList());

        IPage<SysUserDto> resultPage = new Page<>(userPage.getCurrent(), userPage.getSize(), userPage.getTotal());
        resultPage.setRecords(dtoList);
        return ReqResult.success(resultPage);
    }

    @RequireResource(ROLE_MANAGE_BIND_USER)
    @Operation(summary = "角色绑定用户（全量覆盖）")
    @PostMapping("/bind-user")
    public ReqResult<Void> bindUser(@RequestBody SysRoleBindUserDto dto) {
        if (dto == null) {
            return ReqResult.error("参数不能为空");
        }
        sysRoleApplicationService.roleBindUser(dto.getRoleId(), dto.getUserIds(), getUser());
        return ReqResult.success();
    }

    @RequireResource(ROLE_MANAGE_BIND_USER)
    @Operation(summary = "角色添加用户")
    @PostMapping("/add-user")
    public ReqResult<Void> addUser(@RequestBody SysRoleBindUserSingleDto dto) {
        if (dto == null || dto.getRoleId() == null || dto.getUserId() == null) {
            return ReqResult.error("角色ID和用户ID不能为空");
        }
        sysRoleApplicationService.roleAddUser(dto.getRoleId(), dto.getUserId(), getUser());
        return ReqResult.success();
    }

    @RequireResource(ROLE_MANAGE_BIND_USER)
    @Operation(summary = "角色移除用户")
    @PostMapping("/remove-user")
    public ReqResult<Void> removeUser(@RequestBody SysRoleBindUserSingleDto dto) {
        if (dto == null || dto.getRoleId() == null || dto.getUserId() == null) {
            return ReqResult.error("角色ID和用户ID不能为空");
        }
        sysRoleApplicationService.roleRemoveUser(dto.getRoleId(), dto.getUserId(), getUser());
        return ReqResult.success();
    }

    @RequireResource(ROLE_MANAGE_BIND_DATA)
    @Operation(summary = "查询角色数据权限")
    @GetMapping("/data-permission/{roleId}")
    public ReqResult<SysDataPermissionBindDto> getRoleDataPermission(@PathVariable Long roleId) {
        if (roleId == null) {
            return ReqResult.error("参数不能为空");
        }
        SysDataPermission dataPermission = sysDataPermissionApplicationService.getByTarget(PermissionTargetTypeEnum.ROLE, roleId);
        SysDataPermissionBindDto result = SysDataPermissionConverter.toDto(dataPermission);
        result = result == null ? new SysDataPermissionBindDto() : result;

        result.setModelIds(sysSubjectPermissionApplicationService.listSubjectIdsByTarget(
                PermissionTargetTypeEnum.ROLE, roleId, PermissionSubjectTypeEnum.MODEL));
        result.setAgentIds(sysSubjectPermissionApplicationService.listSubjectIdsByTarget(
                PermissionTargetTypeEnum.ROLE, roleId, PermissionSubjectTypeEnum.AGENT));
        result.setPageAgentIds(sysSubjectPermissionApplicationService.listSubjectIdsByTarget(
                PermissionTargetTypeEnum.ROLE, roleId, PermissionSubjectTypeEnum.PAGE));
        Map<String, String> openApiConfigMap = sysSubjectPermissionApplicationService.listSubjectKeyConfigByTarget(
                PermissionTargetTypeEnum.ROLE, roleId, PermissionSubjectTypeEnum.OPEN_API);
        List<SysDataPermissionBindDto.OpenApiConfig> openApiConfigs = openApiConfigMap.entrySet().stream()
                .map(entry -> {
                    SysDataPermissionBindDto.OpenApiConfig config = new SysDataPermissionBindDto.OpenApiConfig();
                    config.setKey(entry.getKey());
                    if (StringUtils.isNotBlank(entry.getValue())) {
                        try {
                            Map<String, Integer> valueMap = JsonSerializeUtil.parseObject(entry.getValue(), new TypeReference<Map<String, Integer>>() {});
                            config.setRpm(valueMap == null ? null : valueMap.get("rpm"));
                            config.setRpd(valueMap == null ? null : valueMap.get("rpd"));
                        } catch (Exception ignore) {
                            config.setRpm(null);
                            config.setRpd(null);
                        }
                    }
                    return config;
                })
                .toList();
        result.setOpenApiConfigs(openApiConfigs);

        result.setKnowledgeIds(sysSubjectPermissionApplicationService.listSubjectIdsByTarget(
                PermissionTargetTypeEnum.ROLE, roleId, PermissionSubjectTypeEnum.KNOWLEDGE));

        return ReqResult.success(result);
    }

    @RequireResource(ROLE_MANAGE_BIND_DATA)
    @Operation(summary = "角色绑定数据权限（全量覆盖）")
    @PostMapping("/bind-data-permission")
    public ReqResult<Void> bindDataPermission(@RequestBody SysRoleBindDataPermissionDto dto) {
        if (dto == null || dto.getRoleId() == null) {
            return ReqResult.error("角色ID不能为空");
        }
        SysDataPermissionBindDto bindDto = dto.getDataPermission();
        SysDataPermissionConverter.validateBindDto(bindDto);
        SysDataPermission dataPermission = SysDataPermissionConverter.toEntity(bindDto);
        sysRoleApplicationService.bindDataPermission(dto.getRoleId(), dataPermission, getUser());
        return ReqResult.success();
    }

    @RequireResource(ROLE_MANAGE_BIND_MENU)
    @Operation(summary = "角色绑定菜单（全量覆盖）")
    @PostMapping("/bind-menu")
    public ReqResult<Void> bindMenu(@RequestBody SysRoleBindMenuDto dto) {
        if (dto == null) {
            return ReqResult.error("参数不能为空");
        }
        RoleBindMenuModel model = RoleBindMenuModelConverter.convertToModel(dto);
        sysRoleApplicationService.bindMenu(model, getUser());
        return ReqResult.success();
    }

    @RequireResource(ROLE_MANAGE_BIND_MENU)
    @Operation(summary = "查询角色已绑定的菜单（树形结构）")
    @GetMapping("/list-menu/{roleId}")
    public ReqResult<List<MenuNodeDto>> getMenuListByRoleId(@PathVariable Long roleId) {
        if (roleId == null) {
            return ReqResult.error("参数不能为空");
        }
        
        // 获取处理好的菜单树（已包含资源详细信息）
        List<MenuNode> menuNodeList = sysRoleApplicationService.getMenuTreeByRoleId(roleId);
        
        // 转换为DTO
        List<MenuNodeDto> menuDtoList = MenuNodeConverter.convertMenuTreeToDtoTree(menuNodeList);

        I18nUtil.replaceSystemMessage(menuDtoList);
        return ReqResult.success(menuDtoList);
    }

}