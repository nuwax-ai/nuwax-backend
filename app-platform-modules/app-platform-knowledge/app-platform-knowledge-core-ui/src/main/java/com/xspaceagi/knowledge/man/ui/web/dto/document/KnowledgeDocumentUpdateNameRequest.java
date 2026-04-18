package com.xspaceagi.knowledge.man.ui.web.dto.document;

import com.xspaceagi.knowledge.domain.model.KnowledgeDocumentModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Schema(description = "知识库文档-更改文件名称")
@Getter
@Setter
public class KnowledgeDocumentUpdateNameRequest implements Serializable {


    @Schema(description = "文档ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Document ID is required")
    private Long docId;

    @Schema(description = "文档名称")
    private String name;


    public static KnowledgeDocumentModel convert2Model(KnowledgeDocumentUpdateNameRequest updateDto) {
        KnowledgeDocumentModel knowledgeDocumentModel = new KnowledgeDocumentModel();
        knowledgeDocumentModel.setId(updateDto.getDocId());
        knowledgeDocumentModel.setKbId(null);
        knowledgeDocumentModel.setName(updateDto.getName());

        return knowledgeDocumentModel;

    }

}
