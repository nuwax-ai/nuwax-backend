package com.xspaceagi.userapp.web.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "应用日志源查询请求")
public class UserAppLogsSourcesQueryReq implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "应用ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long appId;

    @Schema(description = "环境：dev 开发环境（默认）；prod 发布环境")
    private String env;
}
