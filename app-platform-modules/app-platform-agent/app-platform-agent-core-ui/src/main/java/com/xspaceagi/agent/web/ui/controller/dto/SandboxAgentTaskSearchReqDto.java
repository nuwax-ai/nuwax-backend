package com.xspaceagi.agent.web.ui.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Schema(name = "搜索任务请求")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class SandboxAgentTaskSearchReqDto {

    @Schema(description = "按任务主题搜索的关键词，缺省返回最近任务（不限状态）")
    private String keyword;

    @Schema(description = "返回条数上限，默认 20，最大 50")
    private Integer limit;
}
