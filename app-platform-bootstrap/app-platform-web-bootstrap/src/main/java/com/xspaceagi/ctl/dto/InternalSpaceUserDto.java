package com.xspaceagi.ctl.dto;

import java.io.Serializable;
import java.util.Date;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 内部调用接口「空间成员」条目。
 * <p>
 * 不直接复用应用层的 {@code SpaceUserDto}：内部契约需要能独立演进，且 role 在此固定为字符串，
 * 避免枚举名调整直接破坏调用方解析。
 */
@Data
public class InternalSpaceUserDto implements Serializable {

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "用户名，无则空串")
    private String userName;

    @Schema(description = "昵称，无则空串")
    private String nickName;

    @Schema(description = "头像地址")
    private String avatar;

    @Schema(description = "空间ID")
    private Long spaceId;

    @Schema(description = "空间角色：Owner / Admin / User")
    private String role;

    @Schema(description = "加入时间")
    private Date created;

    @Schema(description = "更新时间")
    private Date modified;
}
