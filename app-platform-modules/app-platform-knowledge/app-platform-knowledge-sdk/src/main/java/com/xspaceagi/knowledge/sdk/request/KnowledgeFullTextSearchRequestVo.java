package com.xspaceagi.knowledge.sdk.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.List;

/**
 * 知识库全文检索请求 VO
 * 
 * <p><b>最简使用（只需2个参数）：</b></p>
 * <pre>
 * KnowledgeFullTextSearchRequestVo request = new KnowledgeFullTextSearchRequestVo();
 * request.setKbId(123L);              // 必填：知识库ID
 * request.setQueryText("Spring Boot"); // 必填：查询文本
 * // 其他参数都有默认值，无需设置
 * </pre>
 * 
 * @author system
 * @date 2025-03-31
 */
@Getter
@Setter
@Schema(description = "知识库全文检索请求")
public class KnowledgeFullTextSearchRequestVo implements Serializable {

    private static final long serialVersionUID = 1L;

    // ==================== 必填参数 ==================== //

    @Schema(
        description = "租户ID（RPC调用必填）", 
        requiredMode = Schema.RequiredMode.REQUIRED, 
        example = "1"
    )
    @NotNull(message = "Tenant ID is required")
    private Long tenantId;

    @Schema(
        description = "知识库ID列表（可选，不传则检索所有知识库）", 
        requiredMode = Schema.RequiredMode.NOT_REQUIRED, 
        example = "[123, 456]"
    )
    private List<Long> kbIds;

    @Schema(
        description = "全文检索查询文本（自然语言，系统自动分词）", 
        requiredMode = Schema.RequiredMode.REQUIRED, 
        example = "Spring Boot 开发框架"
    )
    @NotBlank(message = "Query text is required")
    private String queryText;

    // ==================== 可选参数（有默认值）==================== //

    @Schema(
        description = "返回结果数量（Top-K），默认10", 
        requiredMode = Schema.RequiredMode.NOT_REQUIRED, 
        example = "10"
    )
    @Min(value = 1, message = "Result size must be at least 1")
    @Max(value = 100, message = "Result size must be at most 100")
    private Integer topK = 10;

    @Schema(
        description = "指定文档ID列表（可选，不指定则检索所有文档）", 
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        example = "[1, 2, 3]"
    )
    private List<Long> docIds;

}

