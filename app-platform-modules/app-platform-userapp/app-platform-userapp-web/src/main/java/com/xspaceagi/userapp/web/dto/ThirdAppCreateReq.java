package com.xspaceagi.userapp.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 创建三方应用请求。
 * <p>
 * 回调地址与主页地址不在此处填写：建行后经 {@code POST /setting/save} 维护，
 * 与设置页的编辑语义保持一致。
 */
@Data
@Schema(description = "创建三方应用请求")
public class ThirdAppCreateReq {

    @Schema(description = "空间ID；不传则取当前用户的个人空间")
    private Long spaceId;

    @Schema(description = "应用名称；留空按当前语言取默认名")
    private String name;

    @Schema(description = "应用描述")
    private String description;

    @Schema(description = "应用图标")
    private String icon;
}
