package com.xspaceagi.userapp.web.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "更新用户自定义应用基本信息请求")
public class UserAppUpdateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "应用ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "应用名称")
    private String name;

    @Schema(description = "应用描述")
    private String description;

    @Schema(description = "应用图标")
    private String icon;

    @Schema(description = "封面图片")
    private String coverImg;
}
