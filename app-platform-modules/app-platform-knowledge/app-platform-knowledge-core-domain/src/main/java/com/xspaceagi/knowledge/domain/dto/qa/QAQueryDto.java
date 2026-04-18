package com.xspaceagi.knowledge.domain.dto.qa;

import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.knowledge.sdk.request.KnowledgeQaRequestVo;
import com.xspaceagi.system.spec.utils.MySpringBeanContextUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "知识库内问答查询")
public class QAQueryDto implements Serializable {

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

    public static QAQueryEmbeddingDto convertTEmbeddingDto(QAQueryDto qaQueryDto,Long embeddingModelId) {
        var modelApplicationService = MySpringBeanContextUtil.get(ModelApplicationService.class);

        var floats = modelApplicationService.embeddings(List.of(qaQueryDto.getQuestion()),embeddingModelId).get(0);
        List<BigDecimal> embedding = QAEmbeddingDto.convertToBigDecimalList(floats);

        return QAQueryEmbeddingDto.builder()
                .kbId(qaQueryDto.getKbId())
                .topK(qaQueryDto.getTopK())
                .ignoreDocStatus(qaQueryDto.isIgnoreDocStatus())
                .embedding(embedding)
                .build();
    }

    public static QAQueryDto convertToDto(KnowledgeQaRequestVo qaQueryDto) {
        QAQueryDto qAQueryDto = new QAQueryDto();
        qAQueryDto.setKbId(qaQueryDto.getKbId());
        qAQueryDto.setQuestion(qaQueryDto.getQuestion());
        qAQueryDto.setTopK(qaQueryDto.getTopK());
        qAQueryDto.setIgnoreDocStatus(qaQueryDto.isIgnoreDocStatus());
        return qAQueryDto;

    }


}
