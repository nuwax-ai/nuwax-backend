package com.xspaceagi.userapp.infra.dao.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 用户×项目 工作目录绑定（用户级目录） */
@TableName(value = "user_project_workspace", autoResultMap = true)
@Data
public class UserProjectWorkspace {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    private Long userId;

    private String projectType;

    private Long projectId;

    private String fileWorkspacePath;

    /** 用户级 agent 执行路径（执行容器视角；自定义目录时与文件路径一致） */
    private String agentWorkspacePath;

    private Long sandboxId;

    private Date created;

    private Date modified;
}
