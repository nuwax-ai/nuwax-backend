package com.xspaceagi.knowledge.domain.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档解析状态记录模型
 *
 * @author system
 * @date 2026-05-08
 */
@Data
@Schema(description = "文档解析状态记录")
public class DocumentParseStatusRecordModel {

    @Schema(description = "主键")
    private Integer id;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "执行状态（1:执行成功、2：执行失败、0进行中）")
    private String executeState;

    @Schema(description = "成功条数")
    private Long successCount;

    @Schema(description = "失败条数")
    private Long errorCount;

    @Schema(description = "执行开始时间")
    private LocalDateTime startTime;

    @Schema(description = "执行结束时间")
    private LocalDateTime endTime;

    @Schema(description = "存放完成了哪些知识库同步，需要包含同步成功或失败的状态")
    private String executeContent;

    @Schema(description = "执行的异常日志")
    private String executeErrorLogs;
}
