package com.xspaceagi.im.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 飞书群组列表响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeishuChatListRespDto {

    @Schema(description = "群组列表")
    private List<FeishuChatDto> items;

    @Schema(description = "分页标记，用于获取下一页")
    private String pageToken;

    @Schema(description = "是否还有更多数据")
    private Boolean hasMore;
}
