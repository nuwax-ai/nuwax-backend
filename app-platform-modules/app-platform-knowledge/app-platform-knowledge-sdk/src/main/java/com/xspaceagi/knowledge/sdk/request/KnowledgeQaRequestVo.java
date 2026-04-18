package com.xspaceagi.knowledge.sdk.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
@Schema(description = "知识库内问答查询")
public class KnowledgeQaRequestVo implements Serializable {

    @Schema(description = "知识库ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Knowledge base ID is required")
    private Long kbId;

    @Schema(description = "问题", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Question is required")
    private String question;

    @Schema(description = "top-K值", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "top-K is required")
    private int topK;

    @Schema(description = "是否忽略文档状态", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "ignoreDocStatus flag is required")
    private boolean ignoreDocStatus;

    private boolean ignoreTenantId;

}
