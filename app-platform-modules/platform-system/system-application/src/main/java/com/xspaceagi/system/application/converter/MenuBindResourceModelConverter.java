package com.xspaceagi.system.application.converter;

import com.xspaceagi.system.domain.model.MenuNode;
import com.xspaceagi.system.domain.model.ResourceNode;
import com.xspaceagi.system.spec.enums.BindTypeEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.application.dto.permission.MenuNodeDto;
import com.xspaceagi.system.application.dto.permission.ResourceNodeDto;
import com.xspaceagi.system.application.dto.permission.SysMenuBindResourceDto;
import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单资源绑定 DTO -> Domain 模型转换器
 */
public class MenuBindResourceModelConverter {

    public static MenuNode convertToMenuNode(SysMenuBindResourceDto dto) {
        if (dto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemParamRequired);
        }
        MenuNodeDto menuNodeDto = new MenuNodeDto();
        menuNodeDto.setId(dto.getMenuId());
        menuNodeDto.setResourceTree(dto.getResourceTree());

        MenuNode model = convertToMenuNode(menuNodeDto);
        return model;
    }

    // 转换后资源是树形结构，不做扁平化处理
    public static MenuNode convertToMenuNode(MenuNodeDto menuNodeDto) {
        MenuNode menuNode = new MenuNode();
        menuNode.setId(menuNodeDto.getId());
        Integer menuBindType = menuNodeDto.getMenuBindType() != null ? menuNodeDto.getMenuBindType() : BindTypeEnum.NONE.getCode();
        menuNode.setMenuBindType(menuBindType);

        List<ResourceNode> resourceTree = convertResourceTree(menuNodeDto.getResourceTree());
        menuNode.setResourceTree(resourceTree);

        // 如果resourceBindType为PART，从resourceTree中提取所有资源ID，并保留完整资源树
//        if (BindTypeEnum.PART.getCode().equals(resourceBindType)) {
//            // 转换ResourceNode
//            List<ResourceNode> resourceTree = convertResourceTree(menuNodeDto.getResourceTree());
//            menuNode.setResourceTree(resourceTree);
//        } else {
//            menuNode.setResourceTree(null);
//        }
        return menuNode;
    }

    /**
     * 转换资源树。支持两种格式：
     * 1. 树形结构（含 children）
     * 2. 扁平列表（无 children，仅传叶子节点，含 parentId）
     */
    public static List<ResourceNode> convertResourceTree(List<ResourceNodeDto> nodes) {
        if (CollectionUtils.isEmpty(nodes)) {
            return new ArrayList<>();
        }

        List<ResourceNode> result = new ArrayList<>();
        for (ResourceNodeDto node : nodes) {
            ResourceNode resourceNode = new ResourceNode();
            resourceNode.setId(node.getId());
            resourceNode.setParentId(node.getParentId());
            Integer resourceBindType = node.getResourceBindType() != null ? node.getResourceBindType() : BindTypeEnum.NONE.getCode();
            resourceNode.setResourceBindType(resourceBindType);
            resourceNode.setCode(node.getCode());

            if (!CollectionUtils.isEmpty(node.getChildren())) {
                resourceNode.setChildren(convertResourceTree(node.getChildren()));
            }
            result.add(resourceNode);
        }
        return result;
    }

}

