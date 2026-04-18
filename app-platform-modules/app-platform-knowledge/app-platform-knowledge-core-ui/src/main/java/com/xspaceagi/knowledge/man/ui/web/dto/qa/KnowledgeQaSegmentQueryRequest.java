package com.xspaceagi.knowledge.man.ui.web.dto.qa;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Schema(description = "知识库问答-新增请求参数")
@Getter
@Setter
public class KnowledgeQaSegmentQueryRequest implements Serializable {

    /**
     * 所属空间ID
     */
    @Schema(description = "所属空间ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long spaceId;

    @Schema(description = "知识库ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Knowledge base ID is required")
    private Long kbId;

    @Schema(description = "问题", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @NotNull(message = "Answer is required")
    private String question;

}
