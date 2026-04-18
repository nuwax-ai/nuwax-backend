package com.xspaceagi.system.application.dto.permission;

import com.xspaceagi.system.spec.annotation.I18n;
import com.xspaceagi.system.spec.annotation.I18nField;
import com.xspaceagi.system.spec.enums.ResourceTypeEnum;
import com.xspaceagi.system.spec.enums.SourceEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.apache.commons.collections4.CollectionUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@I18n(module = "PermissionResource")
@Data
@Schema(description = "资源树节点")
public class ResourceNodeDto implements Serializable {

    @Schema(description = "ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "父级ID")
    private Long parentId;

    @Schema(description = "资源绑定类型 0:未绑定 1:全部绑定 2:部分绑定")
    private Integer resourceBindType;

    @I18nField(subObj = true)
    @Schema(description = "子资源列表")
    private List<ResourceNodeDto> children;

    @I18nField(keyPrefix = true)
    @Schema(description = "编码")
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

    /**
     * @see ResourceTypeEnum
     */
    @Schema(description = "类型 1:模块 2:组件")
    private Integer type;

    @Schema(description = "访问路径")
    private String path;

    @Schema(description = "图标")
    private String icon;

    @Schema(description = "排序")
    private Integer sortIndex;

    @Schema(description = "状态 1:启用 0:禁用")
    private Integer status;

    @Schema(description = "创建人ID")
    private Long creatorId;

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
     * 资源树扁平化处理
     */
    @Schema(hidden = true)
    public static List<ResourceNodeDto> flattenResourceTree(List<ResourceNodeDto> nodes) {
        if (CollectionUtils.isEmpty(nodes)) {
            return new ArrayList<>();
        }

        List<ResourceNodeDto> result = new ArrayList<>();
        for (ResourceNodeDto node : nodes) {
            if (node.getId() != null) {
                // 创建新节点，不包含children
                ResourceNodeDto flatNode = new ResourceNodeDto();
                flatNode.setId(node.getId());
                flatNode.setResourceBindType(node.getResourceBindType());
                flatNode.setCode(node.getCode());
                result.add(flatNode);
            }
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                result.addAll(flattenResourceTree(node.getChildren()));
            }
        }
        return result;
    }

}
