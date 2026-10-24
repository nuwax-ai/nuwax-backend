package com.xspaceagi.userapp.domain.model;

import java.util.Date;

import lombok.Data;

/**
 * 应用任务
 */
@Data
public class UserAppTaskModel {

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

    /** 执行任务的沙箱ID（轮询/取消时复用） */
    private String sandboxId;

    /** 活跃任务槽位键：pending/running 时为 "appId:守卫组"（dev/build），终态置 NULL；同组互斥、跨组并行 */
    private String activeGuard;

    /** 构建发起时项目工作区 git commit（HEAD hash，仅 build 任务有值，抓取失败为空） */
    private String gitCommit;

    private Date created;

    private Date modified;
}
