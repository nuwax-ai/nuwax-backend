package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "敏感词创建请求")
public class SensitiveWordCreateDto {

    @NotBlank(message = "Word is required")
    @Size(max = 255, message = "Word长度不能超过255")
    @Schema(description = "敏感词（包含匹配存明文，正则匹配存表达式）", required = true)
    private String word;

    @NotBlank(message = "Category is required")
    @Schema(description = "分类：ILLEGAL-违法违禁、ADVERTISING-广告导流、PRIVACY_LEAK-隐私泄露、COMPETITOR-竞品词", required = true)
    private String category;

    @NotBlank(message = "MatchType is required")
    @Schema(description = "匹配方式：CONTAIN-包含、REGEX-正则", required = true)
    private String matchType;

    @NotBlank(message = "Action is required")
    @Schema(description = "触发策略：DISCONNECT-断开连接、REPLACE-替换", required = true)
    private String action;

    @Size(max = 1, message = "替换字符只能是一个字符")
    @Schema(description = "替换字符，单个字符，触发策略为REPLACE时生效，为空默认*")
    private String replaceChar;

    @Schema(description = "状态：1-启用、0-禁用，默认启用")
    private Integer status;
}
