package com.xspaceagi.ctl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 内部接口：在租户下创建空间的请求体。
 * <p>tenantId 走 query 参数，与其它内部接口一致。
 * <p><b>创建者必须由调用方指定</b>（{@code ownerUserId}）：{@code space.creator_id} 是 NOT NULL，
 * 且底层建空间时会自动把创建者写成空间成员并置为 Owner；内部接口没有登录态，无法自行推导。
 */
@Data
@Schema(description = "空间创建请求")
public class InternalSpaceCreateRequest {

    @Schema(description = "创建者用户ID，必须是该租户下的有效用户。创建后该用户自动成为空间 Owner 成员",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "10086")
    private Long ownerUserId;

    @Schema(description = "空间名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "市场部群组")
    private String name;

    @Schema(description = "空间描述，可空")
    private String description;

    @Schema(description = "空间图标，可空（空则用平台默认图标）")
    private String icon;

    @Schema(description = "空间类型：当前只接受 Group 与 DM（不传视为 Group）；传 Personal / Team / Class 一律拒绝",
            example = "Group")
    private String type;

    @Schema(description = "私聊对方用户ID，可选。仅 type=DM 时有效：传入后建空间时把该用户一并写成成员（role=User）。"
            + "type 非 DM 时传本参数会被拒绝（避免误以为已生效）；不能与 ownerUserId 相同")
    private Long peerUserId;
}
