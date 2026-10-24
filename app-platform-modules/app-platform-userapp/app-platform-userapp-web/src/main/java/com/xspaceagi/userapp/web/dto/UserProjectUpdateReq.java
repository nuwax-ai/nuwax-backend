package com.xspaceagi.userapp.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "更新常规项目基本信息请求（传 null 的字段不更新；仅 Normal 类型）")
public class UserProjectUpdateReq {

    @Schema(description = "项目ID")
    private Long id;

    @Schema(description = "项目名称")
    private String name;

    @Schema(description = "项目描述")
    private String description;

    @Schema(description = "项目图标")
    private String icon;
}
