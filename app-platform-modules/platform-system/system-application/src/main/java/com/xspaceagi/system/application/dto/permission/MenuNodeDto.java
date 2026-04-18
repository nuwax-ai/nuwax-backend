package com.xspaceagi.system.application.dto.permission;

import com.xspaceagi.system.spec.annotation.I18n;
import com.xspaceagi.system.spec.annotation.I18nField;
import com.xspaceagi.system.spec.enums.OpenTypeEnum;
import com.xspaceagi.system.spec.enums.SourceEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.apache.commons.collections4.CollectionUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 菜单树节点
 */
@I18n(module = "PermissionMenu")
@Data
@Schema(description = "菜单树节点")
public class MenuNodeDto implements Serializable {

    @Schema(description = "菜单ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "父级ID")
    private Long parentId;

    @Schema(description = "子菜单绑定类型 0:未绑定 1:全部绑定 2:部分绑定")
    private Integer menuBindType;

    @I18nField(subObj = true)
    @Schema(description = "子菜单列表")
    private List<MenuNodeDto> children;

    @I18nField(subObj = true)
    @Schema(description = "资源树")
    private List<ResourceNodeDto> resourceTree;

    @I18nField(subObj = true)
    @Schema(description = "资源列表", hidden = true)
    private List<ResourceNodeDto> resourceNodes;

    @I18nField(keyPrefix = true)
    @Schema(description = "资源码")
    private String code;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "描述")
    private String description;

    /**
     * @see SourceEnum
     */
    @Schema(description = "来源 1:系统内置 2:用户自定义")
    private Integer source;

    @Schema(description = "访问路径")
    private String path;

    /**
     * @see OpenTypeEnum
     */
    @Schema(description = "打开方式 1-当前标签页打开 2-新标签页打开")
    private Integer openType;

    @Schema(description = "图标")
    private String icon;

    @Schema(description = "排序")
    private Integer sortIndex;

    @Schema(description = "状态 1:启用 0:禁用")
    private Integer status;

    @Schema(description = "创建人")
    private String creator;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "修改人ID")
    private Long modifierId;

    @Schema(description = "修改人")
    private String modifier;

    @Schema(description = "修改时间")
    private Date modified;

    /**
     * 将菜单树扁平化为列表
     */
    @Schema(hidden = true)
    public static List<MenuNodeDto> flattenMenuTree(List<MenuNodeDto> nodes) {
        if (CollectionUtils.isEmpty(nodes)) {
            return new ArrayList<>();
        }

        List<MenuNodeDto> result = new ArrayList<>();
        for (MenuNodeDto node : nodes) {
            if (node.getId() == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemMenuIdNullWithNodeName, node.getName());
            } else {
                // 创建新节点，不包含children
                MenuNodeDto flatNode = new MenuNodeDto();
                flatNode.setId(node.getId());
                flatNode.setMenuBindType(node.getMenuBindType());
                flatNode.setResourceTree(node.getResourceTree());
                result.add(flatNode);
            }

            if (CollectionUtils.isNotEmpty(node.getChildren())) {
                result.addAll(flattenMenuTree(node.getChildren()));
            }
        }
        return result;
    }
}
