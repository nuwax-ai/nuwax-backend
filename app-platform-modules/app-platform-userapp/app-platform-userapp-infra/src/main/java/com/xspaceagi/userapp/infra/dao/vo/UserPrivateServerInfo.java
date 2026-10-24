package com.xspaceagi.userapp.infra.dao.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "私服端口信息")
@Data
public class UserPrivateServerInfo {

    @Schema(description = "管理端口（agent API）")
    private Integer agentPort;

    @Schema(description = "交互流量端口（用户流量入口）")
    private Integer vncPort;

    @Schema(description = "文件服务端口")
    private Integer fileServerPort;
}
