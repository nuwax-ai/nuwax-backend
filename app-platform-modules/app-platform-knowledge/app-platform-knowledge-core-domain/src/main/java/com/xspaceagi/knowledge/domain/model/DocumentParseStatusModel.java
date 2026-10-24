package com.xspaceagi.knowledge.domain.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档三元组解析状态模型
 *
 * @author system
 * @date 2026-04-16
 */
@Data
@Schema(description = "文档三元组解析状态")
public class DocumentParseStatusModel {

    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "文档ID")
    private Long documentId;

    @Schema(description = "知识库ID")
    private Long kbId;

    @Schema(description = "三元组解析状态：0-未开始，1-进行中，2-成功，3-禁用，10-失败")
    private Integer tripleStatus;

    @Schema(description = "任务ID")
    private Long taskId;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "创建时间")
    private LocalDateTime created;

    @Schema(description = "更新时间")
    private LocalDateTime modified;
}
