package com.xspaceagi.im.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * IM 渠道配置连通性测试响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "IM 渠道配置连通性测试响应")
public class ImChannelConfigTestResponse {

    @Schema(description = "是否连通")
    private Boolean success;

    @Schema(description = "测试消息")
    private String message;

    @Schema(description = "返回的详细信息（如机器人名称等）")
    private String detail;

}
