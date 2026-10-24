package com.xspaceagi.ctl.dto;

import java.io.Serializable;
import java.util.Date;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 内部调用接口「空间」条目。
 * <p>
 * 不直接复用应用层的 {@code SpaceDto}：内部契约需要能独立演进，且 type 在此固定为字符串
 * （避免枚举名调整直接破坏调用方解析），也不下发 i18n 与权限相关字段。
 */
@Data
public class InternalSpaceDto implements Serializable {

    @Schema(description = "空间ID")
    private Long spaceId;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "空间名称")
    private String name;

    @Schema(description = "空间描述")
    private String description;

    @Schema(description = "空间图标")
    private String icon;

    @Schema(description = "空间类型：Personal / Team / Class / Group")
    private String type;

    @Schema(description = "创建者用户ID")
    private Long creatorId;

    @Schema(description = "创建时间")
    private Date created;
}
