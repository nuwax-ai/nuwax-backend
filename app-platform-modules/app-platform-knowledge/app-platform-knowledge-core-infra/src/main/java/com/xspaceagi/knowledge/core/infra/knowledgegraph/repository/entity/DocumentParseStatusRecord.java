package com.xspaceagi.knowledge.core.infra.knowledgegraph.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档解析状态记录表
 *
 * @TableName document_parse_status_record
 */
@TableName(value = "document_parse_status_record")
@Data
public class DocumentParseStatusRecord {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * 创建时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    /**
     * 执行状态（1:执行成功、2：执行失败、0进行中）
     */
    @TableField("execute_state")
    private String executeState;

    /**
     * 成功条数
     */
    @TableField("success_count")
    private Long successCount;

    /**
     * 失败条数
     */
    @TableField("error_count")
    private Long errorCount;

    /**
     * 执行开始时间
     */
    @TableField("start_time")
    private LocalDateTime startTime;

    /**
     * 执行结束时间
     */
    @TableField("end_time")
    private LocalDateTime endTime;

    /**
     * 存放完成了哪些知识库同步，需要包含同步成功或失败的状态
     */
    @TableField("execute_content")
    private String executeContent;

    /**
     * 执行的异常日志
     */
    @TableField("execute_error_logs")
    private String executeErrorLogs;
}
