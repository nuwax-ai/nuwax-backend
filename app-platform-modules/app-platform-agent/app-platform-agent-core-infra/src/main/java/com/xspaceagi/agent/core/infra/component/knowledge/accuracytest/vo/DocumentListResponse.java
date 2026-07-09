package com.xspaceagi.agent.core.infra.component.knowledge.accuracytest.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.List;

/**
 * 文档列表响应
 */
@Getter
@Setter
@Schema(description = "文档列表响应")
public class DocumentListResponse implements Serializable {

    @Schema(description = "文档列表")
    private List<DocumentItem> documents;

    @Getter
    @Setter
    public static class DocumentItem implements Serializable {
        @Schema(description = "文档ID")
        private Long id;

        @Schema(description = "文档名称")
        private String name;

        @Schema(description = "文件类型")
        private String fileType;

        @Schema(description = "知识库ID")
        private Long knowledgeBaseId;
    }
}