package com.xspaceagi.userapp.infra.dao.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@TableName(value = "userapp_task", autoResultMap = true)
@Data
public class UserAppTask {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    /** 应用ID */
    private Long appId;

    /** 任务所属用户ID */
    private Long userId;

    /** 任务类型 */
    private String taskType;

    /** 下游任务ID */
    private String taskId;

    /** 任务状态：pending/running/completed/failed/cancelled */
    private String status;

    /** 失败原因 */
    private String error;

    /** 执行任务的沙箱ID（轮询/取消时复用） */
    private String sandboxId;

    /** 活跃任务槽位键：pending/running 时为 "appId:守卫组"（dev/build），终态置 NULL（唯一键防同组并发） */
    @TableField(value = "active_guard")
    private String activeGuard;

    /** 构建发起时项目工作区 git commit（HEAD hash，仅 build 任务有值，抓取失败为空） */
    @TableField(value = "git_commit")
    private String gitCommit;

    private Date created;

    private Date modified;
}
