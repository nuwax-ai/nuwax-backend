package com.xspaceagi.system.application.converter;

import com.xspaceagi.system.domain.model.GroupBindMenuModel;
import com.xspaceagi.system.domain.model.MenuNode;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.application.dto.permission.MenuNodeDto;
import com.xspaceagi.system.application.dto.permission.SysGroupBindMenuDto;
import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * DTO到Model转换器
 */
public class GroupBindMenuModelConverter {

    public static GroupBindMenuModel convertToModel(SysGroupBindMenuDto dto) {
        if (dto == null || dto.getGroupId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "用户组ID");
        }

        GroupBindMenuModel model = new GroupBindMenuModel();
        model.setGroupId(dto.getGroupId());

        // 获取所有菜单节点
        List<MenuNodeDto> menuNodes = dto.getAllMenuNodes();
        if (CollectionUtils.isEmpty(menuNodes)) {
            model.setMenuBindResourceList(new ArrayList<>());
            return model;
        }

        // 转换为Domain模型
        List<MenuNode> menuBindInfos = MenuBindModelConverterUtil.convertMenuNodes(menuNodes);
        model.setMenuBindResourceList(menuBindInfos);
        return model;
    }
}
