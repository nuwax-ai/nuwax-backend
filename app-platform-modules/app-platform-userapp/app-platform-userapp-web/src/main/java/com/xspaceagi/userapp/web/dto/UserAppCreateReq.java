package com.xspaceagi.userapp.web.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "创建用户自定义应用请求")
public class UserAppCreateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "空间ID，不传则默认放在个人空间")
    private Long spaceId;

    @Schema(description = "应用名称，不传则使用默认名称")
    private String name;

    @Schema(description = "应用描述")
    private String description;

    @Schema(description = "应用图标")
    private String icon;

    @Schema(description = "沙箱ID")
    private Long sandboxId;

    @Schema(description = "工作目录")
    private String workspacePath;

    @Schema(description = "开发智能体ID")
    private Long devAgentId;

}
