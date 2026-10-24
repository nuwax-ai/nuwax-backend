package com.xspaceagi.userapp.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "创建常规项目请求（仅 NormalProject 类型；UserApp/PageApp 走各自创建入口）")
public class UserProjectCreateReq {

    @Schema(description = "空间ID")
    private Long spaceId;

    @Schema(description = "项目名称")
    private String name;

    @Schema(description = "项目描述")
    private String description;

    @Schema(description = "项目图标")
    private String icon;

    @Schema(description = "沙箱ID，-1 表示云端沙箱")
    private Long sandboxId;

    @Schema(description = "工作空间目录")
    private String workspacePath;

}
