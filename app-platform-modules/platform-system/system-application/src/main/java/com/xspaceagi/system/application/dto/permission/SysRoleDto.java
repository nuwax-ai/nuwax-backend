package com.xspaceagi.system.application.dto.permission;

import com.xspaceagi.system.spec.annotation.I18n;
import com.xspaceagi.system.spec.annotation.I18nField;
import com.xspaceagi.system.spec.enums.SourceEnum;
import com.xspaceagi.system.spec.enums.StatusEnum;
import com.xspaceagi.system.sdk.service.dto.TokenLimit;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@I18n(module = "PermissionRole")
@Data
public class SysRoleDto implements Serializable {

    @Schema(description = "ID")
    private Long id;

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
     * @see StatusEnum
     */
    @Schema(description = "状态 1:启动 0:禁用")
    private Integer status;

    @Schema(description = "排序")
    private Integer sortIndex;

    @Schema(description = "模型ID列表 全部模型传[-1],未选中任何模型不传值")
    private List<Long> modelIds;

    @Schema(description = "token限制")
    private TokenLimit tokenLimit;

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
}