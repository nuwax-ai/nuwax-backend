package com.xspaceagi.knowledge.man.ui.web.dto.document;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.List;

@Schema(description = "知识库文档-查询文档状态")
@Getter
@Setter
public class KnowledgeDocumentQueryStatusRequest implements Serializable {

    @Schema(description = "文档ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Document ID is required")
    private List<Long> docIds;

}
