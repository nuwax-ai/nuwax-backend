package com.xspaceagi.agent.web.ui.controller.api.dto;

import com.xspaceagi.knowledge.sdk.response.KnowledgeQaVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class KbQueryResultDTO {
    @Schema(description = "检索结果")
    private List<KnowledgeQaVo> items;
    @Schema(description = "错误信息")
    private Map<String, Object> error;
}
