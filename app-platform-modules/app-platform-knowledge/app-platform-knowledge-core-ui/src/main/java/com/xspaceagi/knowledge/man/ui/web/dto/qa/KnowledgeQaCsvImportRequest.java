package com.xspaceagi.knowledge.man.ui.web.dto.qa;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

import java.io.Serializable;

@Schema(description = "知识库问答-CSV批量导入请求参数")
@Getter
@Setter
public class KnowledgeQaCsvImportRequest implements Serializable {

    @Schema(description = "知识库ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Knowledge base ID is required")
    private Long kbId;

    @Schema(description = "所属空间ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long spaceId;

    @Schema(description = "CSV文件", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "CSV file is required")
    private MultipartFile file;
} 