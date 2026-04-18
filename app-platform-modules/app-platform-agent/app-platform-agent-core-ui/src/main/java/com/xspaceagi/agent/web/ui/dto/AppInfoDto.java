package com.xspaceagi.agent.web.ui.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotNull;
import java.io.Serializable;

@Data
public class AppInfoDto implements Serializable {

     @Schema(description =  "应用展示名称", required = true)
    private String pubName;

     @Schema(description =  "应用LOGO地址", required = true)
    @NotNull(message = "Logo URL is required")
    private String logoUrl;

     @Schema(description =  "应用描述", required = true)
    @NotNull(message = "App description is required")
    private String description;

     @Schema(description =  "后续接口访问路径")
    private String path;

}
