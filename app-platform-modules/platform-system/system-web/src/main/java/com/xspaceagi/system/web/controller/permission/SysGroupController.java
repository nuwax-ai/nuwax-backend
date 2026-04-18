package com.xspaceagi.system.web.controller.permission;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.system.application.converter.GroupBindMenuModelConverter;
import com.xspaceagi.system.application.converter.MenuNodeConverter;
import com.xspaceagi.system.application.converter.SysDataPermissionConverter;
import com.xspaceagi.system.application.dto.permission.*;
import com.xspaceagi.system.application.service.SysDataPermissionApplicationService;
import com.xspaceagi.system.application.service.SysGroupApplicationService;
import com.xspaceagi.system.application.service.SysSubjectPermissionApplicationService;
import com.xspaceagi.system.domain.model.GroupBindMenuModel;
import com.xspaceagi.system.domain.model.MenuNode;
import com.xspaceagi.system.domain.model.SortIndex;
import com.xspaceagi.system.infra.dao.entity.SysDataPermission;
import com.xspaceagi.system.infra.dao.entity.SysGroup;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.dto.PageQueryVo;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.*;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
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
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Slf4j
@Tag(name = "权限管理-用户组", description = "用户组相关接口")
@RestController
@RequestMapping("/api/system/group")
public class SysGroupController extends BaseController {

    @Resource
    private SysGroupApplicationService sysGroupApplicationService;
    @Resource
    private SysDataPermissionApplicationService sysDataPermissionApplicationService;
    @Resource
    private SysSubjectPermissionApplicationService sysSubjectPermissionApplicationService;

    @RequireResource(USER_GROUP_MANAGE_ADD)
    @Operation(summary = "添加组")
    @PostMapping(value = "/add", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> addGroup(@RequestBody SysGroupAddDto dto) {
        if (dto == null) {
            return ReqResult.error("参数不能为空");
        }
        if (dto.getSource() != null && SourceEnum.isInValid(dto.getSource())) {
            return ReqResult.error("参数source错误");
        }
        if (dto.getStatus() != null && StatusEnum.isInValid(dto.getStatus())) {
            return ReqResult.error("参数status错误");
        }

        SysGroup group = new SysGroup();
        BeanUtils.copyProperties(dto, group);
        sysGroupApplicationService.addGroup(group, getUser());
        return ReqResult.success();
    }

    @RequireResource(USER_GROUP_MANAGE_MODIFY)
    @Operation(summary = "更新组")
    @PostMapping("/update")
    public ReqResult<Void> updateGroup(@RequestBody SysGroupUpdateDto dto) {
        if (dto == null) {
            return ReqResult.error("参数不能为空");
        }
        if (dto.getSource() != null && SourceEnum.isInValid(dto.getSource())) {
            return ReqResult.error("参数source错误");
        }
        if (dto.getStatus() != null && StatusEnum.isInValid(dto.getStatus())) {
            return ReqResult.error("参数status错误");
        }

        SysGroup group = new SysGroup();
        BeanUtils.copyProperties(dto, group);
        group.setCode(null); // code不允许更新
        sysGroupApplicationService.updateGroup(group, getUser());
        return ReqResult.success();
    }

    @RequireResource(USER_GROUP_MANAGE_DELETE)
    @Operation(summary = "删除组")
    @PostMapping("/delete/{groupId}")
    public ReqResult<Void> deleteGroup(@PathVariable Long groupId) {
        if (groupId == null) {
            return ReqResult.error("参数不能为空");
        }
        sysGroupApplicationService.deleteGroup(groupId, getUser());
        return ReqResult.success();
    }

    @RequireResource(USER_GROUP_MANAGE_QUERY)
    @Operation(summary = "根据ID查询组")
    @GetMapping("/{groupId}")
    public ReqResult<SysGroupDto> getGroupById(@PathVariable Long groupId) {
        if (groupId == null) {
            return ReqResult.error("参数不能为空");
        }

        SysGroup group = sysGroupApplicationService.getGroupById(groupId);
        if (group == null) {
            return ReqResult.error("用户组不存在");
        }
        SysGroupDto groupDto = new SysGroupDto();
        BeanUtils.copyProperties(group, groupDto);

//        SysDataPermission dataPermission = sysDataPermissionApplicationService.getByTarget(PermissionTargetTypeEnum.GROUP, group.getId());
//        if (dataPermission != null) {
//            groupDto.setModelIds(dataPermission.getModelIds());
//            groupDto.setTokenLimit(dataPermission.getTokenLimit());
//        }
        I18nUtil.replaceSystemMessage(groupDto);
        return ReqResult.success(groupDto);
    }

    @RequireResource(USER_GROUP_MANAGE_QUERY)
    @Operation(summary = "根据编码查询组")
    @GetMapping("/code/{groupCode}")
    public ReqResult<SysGroupDto> getGroupByCode(@PathVariable String groupCode) {
        if (StringUtils.isBlank(groupCode)) {
            return ReqResult.error("参数不能为空");
        }

        SysGroup group = sysGroupApplicationService.getGroupByCode(groupCode);
        if (group == null) {
            return ReqResult.error("用户组不存在");
        }
        SysGroupDto groupDto = new SysGroupDto();
        BeanUtils.copyProperties(group, groupDto);

//        SysDataPermission dataPermission = sysDataPermissionApplicationService.getByTarget(PermissionTargetTypeEnum.GROUP, group.getId());
//        if (dataPermission != null) {
//            groupDto.setModelIds(dataPermission.getModelIds());
//            groupDto.setTokenLimit(dataPermission.getTokenLimit());
//        }
        I18nUtil.replaceSystemMessage(groupDto);
        return ReqResult.success(groupDto);
    }

    @RequireResource(USER_GROUP_MANAGE_MODIFY)
    @Operation(summary = "调整用户组顺序")
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
        sysGroupApplicationService.batchUpdateGroupSort(sortIndexList, getUser());
        return ReqResult.success();
    }

    @RequireResource(USER_GROUP_MANAGE_QUERY)
    @Operation(summary = "根据条件查询组列表")
    @GetMapping("/list")
    public ReqResult<List<SysGroupDto>> getGroupList(SysGroupQueryDto dto) {
        SysGroup sysGroup = new SysGroup();
        if (dto != null) {
            BeanUtils.copyProperties(dto, sysGroup);
        }

        List<SysGroup> groupList = sysGroupApplicationService.getGroupList(sysGroup);
        if (CollectionUtils.isEmpty(groupList)) {
            return ReqResult.success();
        }
//        List<Long> groupIds = groupList.stream().map(SysGroup::getId).toList();
//        List<SysDataPermission> dataPermissionList = sysDataPermissionApplicationService.getByTargetList(PermissionTargetTypeEnum.GROUP, groupIds);
//        Map<Long, SysDataPermission> permissionMap = dataPermissionList.stream()
//                .collect(Collectors.toMap(SysDataPermission::getTargetId, p -> p, (a, b) -> a));

        List<SysGroupDto> dtoList = groupList.stream().map(r -> {
            SysGroupDto groupDto = new SysGroupDto();
            BeanUtils.copyProperties(r, groupDto);
//            SysDataPermission dataPermission = permissionMap.get(r.getId());
//            if (dataPermission != null) {
//                groupDto.setModelIds(dataPermission.getModelIds());
//                groupDto.setTokenLimit(dataPermission.getTokenLimit());
//            }
            return groupDto;
        }).collect(Collectors.toList());
        I18nUtil.replaceSystemMessage(dtoList);
        return ReqResult.success(dtoList);
    }

    @RequireResource(USER_GROUP_MANAGE_BIND_USER)
    @Operation(summary = "分页查询组已绑定的用户列表，支持按userName模糊筛选")
    @PostMapping("/list-user")
    public ReqResult<IPage<SysUserDto>> getUserListByGroupId(@RequestBody PageQueryVo<SysGroupUserQueryDto> pageQueryVo) {
        if (pageQueryVo == null || pageQueryVo.getQueryFilter() == null || pageQueryVo.getQueryFilter().getGroupId() == null) {
            return ReqResult.error("用户组ID不能为空");
        }
        SysGroupUserQueryDto queryDto = pageQueryVo.getQueryFilter();
        Long groupId = queryDto.getGroupId();
        String userName = queryDto.getUserName();
        long pageNo = pageQueryVo.getPageNo() != null ? pageQueryVo.getPageNo() : 1L;
        long pageSize = pageQueryVo.getPageSize() != null ? pageQueryVo.getPageSize() : 10L;

        IPage<User> userPage = sysGroupApplicationService.getUserPageByGroupId(groupId, userName, pageNo, pageSize);
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

    @RequireResource(USER_GROUP_MANAGE_BIND_DATA)
    @Operation(summary = "查询用户组数据权限")
    @GetMapping("/data-permission/{groupId}")
    public ReqResult<SysDataPermissionBindDto> getGroupDataPermission(@PathVariable Long groupId) {
        if (groupId == null) {
            return ReqResult.error("参数不能为空");
        }
        SysDataPermission dataPermission = sysDataPermissionApplicationService.getByTarget(PermissionTargetTypeEnum.GROUP, groupId);
        SysDataPermissionBindDto result = SysDataPermissionConverter.toDto(dataPermission);
        result = result == null ? new SysDataPermissionBindDto() : result;

        result.setModelIds(sysSubjectPermissionApplicationService.listSubjectIdsByTarget(
                PermissionTargetTypeEnum.GROUP, groupId, PermissionSubjectTypeEnum.MODEL));
        result.setAgentIds(sysSubjectPermissionApplicationService.listSubjectIdsByTarget(
                PermissionTargetTypeEnum.GROUP, groupId, PermissionSubjectTypeEnum.AGENT));
        result.setPageAgentIds(sysSubjectPermissionApplicationService.listSubjectIdsByTarget(
                PermissionTargetTypeEnum.GROUP, groupId, PermissionSubjectTypeEnum.PAGE));
        Map<String, String> openApiConfigMap = sysSubjectPermissionApplicationService.listSubjectKeyConfigByTarget(
                PermissionTargetTypeEnum.GROUP, groupId, PermissionSubjectTypeEnum.OPEN_API);
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
                PermissionTargetTypeEnum.GROUP, groupId, PermissionSubjectTypeEnum.KNOWLEDGE));

        return ReqResult.success(result);
    }

    @RequireResource(USER_GROUP_MANAGE_BIND_DATA)
    @Operation(summary = "组绑定数据权限（全量覆盖）")
    @PostMapping("/bind-data-permission")
    public ReqResult<Void> bindDataPermission(@RequestBody SysGroupBindDataPermissionDto dto) {
        if (dto == null || dto.getGroupId() == null) {
            return ReqResult.error("用户组ID不能为空");
        }
        SysDataPermissionBindDto bindDto = dto.getDataPermission();
        SysDataPermission dataPermission = SysDataPermissionConverter.toEntity(bindDto);
        sysGroupApplicationService.bindDataPermission(dto.getGroupId(), dataPermission, getUser());
        return ReqResult.success();
    }

    @RequireResource(USER_GROUP_MANAGE_BIND_USER)
    @Operation(summary = "组绑定用户（全量覆盖）")
    @PostMapping("/bind-user")
    public ReqResult<Void> bindUser(@RequestBody SysGroupBindUserDto dto) {
        if (dto == null) {
            return ReqResult.error("参数不能为空");
        }
        sysGroupApplicationService.groupBindUser(dto.getGroupId(), dto.getUserIds(), getUser());
        return ReqResult.success();
    }

    @RequireResource(USER_GROUP_MANAGE_BIND_USER)
    @Operation(summary = "用户组添加用户")
    @PostMapping("/add-user")
    public ReqResult<Void> addUser(@RequestBody SysGroupBindUserSingleDto dto) {
        if (dto == null || dto.getGroupId() == null || dto.getUserId() == null) {
            return ReqResult.error("用户组ID和用户ID不能为空");
        }
        sysGroupApplicationService.groupAddUser(dto.getGroupId(), dto.getUserId(), getUser());
        return ReqResult.success();
    }

    @RequireResource(USER_GROUP_MANAGE_BIND_USER)
    @Operation(summary = "用户组移除用户")
    @PostMapping("/remove-user")
    public ReqResult<Void> removeUser(@RequestBody SysGroupBindUserSingleDto dto) {
        if (dto == null || dto.getGroupId() == null || dto.getUserId() == null) {
            return ReqResult.error("用户组ID和用户ID不能为空");
        }
        sysGroupApplicationService.groupRemoveUser(dto.getGroupId(), dto.getUserId(), getUser());
        return ReqResult.success();
    }

    @RequireResource(USER_GROUP_MANAGE_BIND_MENU)
    @Operation(summary = "组绑定菜单（全量覆盖）")
    @PostMapping("/bind-menu")
    public ReqResult<Void> bindMenu(@RequestBody SysGroupBindMenuDto dto) {
        if (dto == null) {
            return ReqResult.error("参数不能为空");
        }
        // 用户组不允许绑定 生态市场/系统管理
        checkForbiddenMenuCodes(dto.getMenuTree());

        GroupBindMenuModel model = GroupBindMenuModelConverter.convertToModel(dto);
        sysGroupApplicationService.bindMenu(model, getUser());
        return ReqResult.success();
    }

    @RequireResource(USER_GROUP_MANAGE_BIND_MENU)
    @Operation(summary = "查询组已绑定的菜单（树形结构）")
    @GetMapping("/list-menu/{groupId}")
    public ReqResult<List<MenuNodeDto>> getMenuListByGroupId(@PathVariable Long groupId) {
        if (groupId == null) {
            return ReqResult.error("参数不能为空");
        }
        // 获取处理好的菜单树（已包含资源详细信息）
        List<MenuNode> menuNodeList = sysGroupApplicationService.getMenuTreeByGroupId(groupId);

        // 转换为DTO
        List<MenuNodeDto> menuDtoList = MenuNodeConverter.convertMenuTreeToDtoTree(menuNodeList);

        I18nUtil.replaceSystemMessage(menuDtoList);
        return ReqResult.success(menuDtoList);
    }

    private void checkForbiddenMenuCodes(List<MenuNodeDto> nodes) {
        if (CollectionUtils.isEmpty(nodes)) {
            return;
        }
        for (MenuNodeDto node : nodes) {
            if (StringUtils.isNotBlank(node.getCode())) {
                if (node.getCode().equals(MenuEnum.ECO_MARKET.getCode()) && node.getMenuBindType() > 0) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGroupCannotBindForbiddenMenu,
                            MenuEnum.ECO_MARKET.getName());
                }
                if (node.getCode().equals(MenuEnum.SYSTEM_MANAGE.getCode()) && node.getMenuBindType() > 0) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGroupCannotBindForbiddenMenu,
                            MenuEnum.SYSTEM_MANAGE.getName());
                }
            }
            if (CollectionUtils.isNotEmpty(node.getChildren())) {
                checkForbiddenMenuCodes(node.getChildren());
            }
        }
    }
}