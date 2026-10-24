package com.xspaceagi.userapp.domain.model;

import java.util.Date;

import lombok.Data;

/** 用户×项目 工作目录绑定（用户级目录） */
@Data
public class UserProjectWorkspaceModel {

    private Long id;

    private Long tenantId;

    private Long userId;

    private String projectType;

    private Long projectId;

    private String fileWorkspacePath;

    /** 用户级 agent 执行路径 */
    private String agentWorkspacePath;

    private Long sandboxId;

    private Date created;

    private Date modified;
}
