package com.xspaceagi.knowledge.domain.dto.qa;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;


@Builder
@Data
@Schema(name = "带嵌入的问答查询")
public class QAQueryEmbeddingDto implements Serializable {
    @Schema(description = "知识库ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Knowledge base ID is required")
    private Long kbId;

    @Schema(description = "嵌入", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Embedding is required")
    private List<BigDecimal> embedding;

    @Schema(description = "top-K值")
    private int topK;

    @Schema(description = "是否忽略文档状态")
    private boolean ignoreDocStatus;
}
