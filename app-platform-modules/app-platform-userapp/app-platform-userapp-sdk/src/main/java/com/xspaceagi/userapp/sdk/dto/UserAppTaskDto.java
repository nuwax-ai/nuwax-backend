package com.xspaceagi.userapp.sdk.dto;

import java.io.Serializable;
import java.util.Date;

import lombok.Data;

/**
 * 应用任务信息
 */
@Data
public class UserAppTaskDto implements Serializable {

    private Long id;

    private Long tenantId;

    /** 应用ID */
    private Long appId;

    /** 任务所属用户ID（应用所有者） */
    private Long userId;

    /** 任务类型 */
    private String taskType;

    /** 下游任务ID */
    private String taskId;

    /** 任务状态 */
    private String status;

    /** 失败原因 */
    private String error;

    /** 执行任务的沙箱ID */
    private String sandboxId;

    /** 构建发起时项目工作区 git commit（仅 build 任务有值，抓取失败为空） */
    private String gitCommit;

    private Date created;

    private Date modified;
}
