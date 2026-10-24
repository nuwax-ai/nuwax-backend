package com.xspaceagi.userapp.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 更新三方应用基本信息请求。
 * <p>
 * 字段语义：<b>传 null 表示不更新</b>；非 null 一律 trim 后写入，
 * 因此传空串可清空 <code>description</code> / <code>icon</code>。
 */
@Data
@Schema(description = "更新三方应用基本信息请求")
public class ThirdAppUpdateReq {

    @NotNull
    @Schema(description = "统一项目 ID（user_project 主键）")
    private Long projectId;

    @Schema(description = "应用名称，≤128 字符；null=不更新，空值会被拒绝")
    private String name;

    @Schema(description = "应用描述，≤512 字符；null=不更新，空串=清空")
    private String description;

    @Schema(description = "应用图标地址；null=不更新，空串=清空")
    private String icon;
}
