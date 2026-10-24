package com.xspaceagi.agent.web.ui.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Schema(name = "查询执行中任务请求")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class SandboxAgentTaskListReqDto {

    @Schema(description = "返回条数上限，默认 20，最大 50")
    private Integer limit;
}
