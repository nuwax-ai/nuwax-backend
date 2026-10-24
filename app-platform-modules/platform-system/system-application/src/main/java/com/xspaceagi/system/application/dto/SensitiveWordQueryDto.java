package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "敏感词分页查询请求")
public class SensitiveWordQueryDto {

    @Schema(description = "敏感词，模糊匹配")
    private String word;

    @Schema(description = "分类：ILLEGAL-违法违禁、ADVERTISING-广告导流、PRIVACY_LEAK-隐私泄露、COMPETITOR-竞品词")
    private String category;

    @Schema(description = "匹配方式：CONTAIN-包含、REGEX-正则")
    private String matchType;

    @Schema(description = "触发策略：DISCONNECT-断开连接、REPLACE-替换")
    private String action;

    @Schema(description = "状态：1-启用、0-禁用")
    private Integer status;
}
