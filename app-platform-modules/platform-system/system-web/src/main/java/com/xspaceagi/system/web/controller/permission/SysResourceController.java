package com.xspaceagi.system.web.controller.permission;

import com.xspaceagi.system.application.dto.permission.*;
import com.xspaceagi.system.application.service.SysResourceApplicationService;
import com.xspaceagi.system.domain.model.SortIndex;
import com.xspaceagi.system.infra.dao.entity.SysResource;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ResourceTypeEnum;
import com.xspaceagi.system.spec.enums.SourceEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.utils.I18nUtil;
import com.xspaceagi.system.web.controller.base.BaseController;
import com.xspaceagi.system.application.converter.ResourceTreeUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Slf4j
@Tag(name = "权限管理-资源", description = "资源相关接口")
@RestController
@RequestMapping("/api/system/resource")
public class SysResourceController extends BaseController {
    
    @Resource
    private SysResourceApplicationService sysResourceApplicationService;

    @RequireResource(RESOURCE_MANAGE_ADD)
    @Operation(summary = "添加资源")
    @PostMapping(value = "/add",produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> addResource(@RequestBody SysResourceAddDto dto) {
        if (dto == null) {
            return ReqResult.error("参数不能为空");
        }
        if (dto.getSource() != null && SourceEnum.isInValid(dto.getSource())) {
            return ReqResult.error("参数source错误");
        }
        if (dto.getStatus() != null && YesOrNoEnum.isInValid(dto.getStatus())) {
            return ReqResult.error("参数status错误");
        }
        if (dto.getType() != null && ResourceTypeEnum.isInValid(dto.getType())) {
            return ReqResult.error("参数type错误");
        }

        SysResource resource = new SysResource();
        BeanUtils.copyProperties(dto, resource);
        sysResourceApplicationService.addResource(resource, getUser());
        return ReqResult.success();
    }

    @RequireResource(RESOURCE_MANAGE_MODIFY)
    @Operation(summary = "更新资源")
    @PostMapping("/update")
    public ReqResult<Void> updateResource(@RequestBody SysResourceUpdateDto dto) {
        if (dto == null) {
            return ReqResult.error("参数不能为空");
        }
        if (dto.getSource() != null && SourceEnum.isInValid(dto.getSource())) {
            return ReqResult.error("参数source错误");
        }
        if (dto.getStatus() != null && YesOrNoEnum.isInValid(dto.getStatus())) {
            return ReqResult.error("参数status错误");
        }
        if (dto.getType() != null && ResourceTypeEnum.isInValid(dto.getType())) {
            return ReqResult.error("参数type错误");
        }

        SysResource resource = new SysResource();
        BeanUtils.copyProperties(dto, resource);
        resource.setCode(null); // code不允许更新
        sysResourceApplicationService.updateResource(resource, getUser());
        return ReqResult.success();
    }

    @RequireResource(RESOURCE_MANAGE_DELETE)
    @Operation(summary = "删除资源")
    @PostMapping("/delete/{resourceId}")
    public ReqResult<Void> deleteResource(@PathVariable Long resourceId) {
        if (resourceId == null) {
            return ReqResult.error("参数不能为空");
        }
        sysResourceApplicationService.deleteResource(resourceId, getUser());
        return ReqResult.success();
    }

    @RequireResource(RESOURCE_MANAGE_QUERY)
    @Operation(summary = "根据ID查询资源")
    @GetMapping("/{resourceId}")
    public ReqResult<ResourceNodeDto> getResourceById(@PathVariable Long resourceId) {
        if (resourceId == null) {
            return ReqResult.error("参数不能为空");
        }
        SysResource resource = sysResourceApplicationService.getResourceById(resourceId);
        if (resource == null) {
            return ReqResult.error("资源不存在");
        }
        ResourceNodeDto dto = new ResourceNodeDto();
        BeanUtils.copyProperties(resource, dto);

        I18nUtil.replaceSystemMessage(dto);
        return ReqResult.success(dto);
    }

    @RequireResource(RESOURCE_MANAGE_QUERY)
    @Operation(summary = "根据编码查询资源")
    @GetMapping("/code/{resourceCode}")
    public ReqResult<ResourceNodeDto> getResourceByCode(@PathVariable String resourceCode) {
        if (StringUtils.isBlank(resourceCode)) {
            return ReqResult.error("参数不能为空");
        }
        SysResource resource = sysResourceApplicationService.getResourceByCode(resourceCode);
        if (resource == null) {
            return ReqResult.error("资源不存在");
        }
        ResourceNodeDto dto = new ResourceNodeDto();
        BeanUtils.copyProperties(resource, dto);

        I18nUtil.replaceSystemMessage(dto);
        return ReqResult.success(dto);
    }

    @RequireResource(RESOURCE_MANAGE_MODIFY)
    @Operation(summary = "调整资源顺序")
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
        sysResourceApplicationService.batchUpdateResourceSort(sortIndexList, getUser());
        return ReqResult.success();
    }

    @RequireResource(RESOURCE_MANAGE_QUERY)
    @Operation(summary = "根据条件查询资源列表（树形结构）")
    @GetMapping("/list")
    public ReqResult<List<ResourceNodeDto>> getResourceList(SysResourceQueryDto queryDto) {
        SysResource sysResource = new SysResource();
        if (queryDto != null) {
            BeanUtils.copyProperties(queryDto, sysResource);
        }
        List<SysResource> resourceList = sysResourceApplicationService.getResourceList(sysResource);
        
        // 构建资源树
        List<ResourceNodeDto> tree = ResourceTreeUtil.buildResourceTree(resourceList);
        
        // 如果 queryDto 为 null 或所有字段都为空（查询完整资源树），需要添加根节点
        if (isQueryEmpty(queryDto)) {
            ResourceNodeDto rootNode = new ResourceNodeDto();
            rootNode.setId(0L);
            rootNode.setCode("root");
            rootNode.setName("根节点");
            rootNode.setParentId(null);
            rootNode.setChildren(tree);
            List<ResourceNodeDto> result = new ArrayList<>();
            result.add(rootNode);

            I18nUtil.replaceSystemMessage(result);
            return ReqResult.success(result);
        }

        I18nUtil.replaceSystemMessage(tree);
        return ReqResult.success(tree);
    }
    
    /**
     * 判断查询条件是否为空
     */
    private boolean isQueryEmpty(SysResourceQueryDto queryDto) {
        if (queryDto == null) {
            return true;
        }
        return StringUtils.isBlank(queryDto.getCode())
                && StringUtils.isBlank(queryDto.getName())
                && queryDto.getSource() == null
                && queryDto.getType() == null
                && queryDto.getParentId() == null
                && queryDto.getStatus() == null;
    }
    
}