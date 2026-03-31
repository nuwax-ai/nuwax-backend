package com.xspaceagi.system.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;

@TableName(value = "schedule_task", autoResultMap = true)
@Data
public class ScheduleTask {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    @TableField(value = "space_id")
    private Long spaceId;

    @TableField(value = "creator_id")
    private Long creatorId;

    @TableField(value = "target_type")
    private String targetType;

    @TableField(value = "task_name")
    private String taskName;

    private String targetId;

    private String taskId;

    private String beanId;

    /**
     * 执行周期
     */
    private String cron;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object params;

    private ScheduleTaskDto.Status status;

    private Date latestExecTime;

    private Date lockTime;

    private Long execTimes;

    private Long maxExecTimes;

    private String error;

    private String serverInfo;

    @Schema(description = "任务修改时间")
    private Date modified;

    @Schema(description = "任务创建时间")
    private Date created;

}
