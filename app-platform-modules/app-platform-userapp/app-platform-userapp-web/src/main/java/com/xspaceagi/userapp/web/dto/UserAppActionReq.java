package com.xspaceagi.userapp.web.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "开发容器操作请求")
public class UserAppActionReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "应用ID")
    private Long appId;

    // 空则取最新发布版本
    @Schema(description = "部署版本")
    private String releaseId;
}
