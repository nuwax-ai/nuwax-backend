package com.xspaceagi.userapp.infra.dao.entity;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.type.EnumTypeHandler;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.userapp.infra.dao.typehandler.BuildVersionListTypeHandler;
import com.xspaceagi.userapp.sdk.dto.SandboxTypeEnum;
import com.xspaceagi.userapp.sdk.dto.UserAppDeployType;
import com.xspaceagi.userapp.sdk.dto.UserAppPublishStatus;
import com.xspaceagi.userapp.sdk.dto.BuildVersionDto;

import lombok.Data;

@TableName(value = "userapp", autoResultMap = true)
@Data
public class UserApp {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    private Long spaceId;

    private Long creatorId;

    private String name;

    private String description;

    private String icon;

    private String coverImg;

    private Long sandboxId;

    @TableField(value = "repo_slug_id")
    private String repoSlugId;

    @TableField(value = "plan_slug_id")
    private String planSlugId;


    @TableField(value = "dev_agent_id")
    private Long devAgentId;

    @TableField(value = "prod_deployed")
    private Boolean prodDeployed;

    /** 生产运行状态：true=运行中，false=已停止；与部署标记解耦，仅部署过后的停止才被代理拦截 */
    @TableField(value = "prod_running")
    private Boolean prodRunning;

    @TableField(value = "prod_release_id")
    private String prodReleaseId;

    @TableField(value = "sandbox_type", typeHandler = EnumTypeHandler.class)
    private SandboxTypeEnum sandboxType;

    @TableField(value = "deploy_type", typeHandler = EnumTypeHandler.class)
    private UserAppDeployType deployType;

    @TableField(value = "deploy_server_id")
    private Long deployServerId;

    @TableField(value = "publish_status", typeHandler = EnumTypeHandler.class)
    private UserAppPublishStatus publishStatus;

    @TableField(value = "build_versions", typeHandler = BuildVersionListTypeHandler.class)
    private List<BuildVersionDto> buildVersions;

    private String devDbUsername;

    private String devDbPassword;

    private String prodDbUsername;

    private String prodDbPassword;

    /** 预删除时间（NULL=正常；非空=已预删除，前端不可见、代理404，保留期满物理删除） */
    @TableField(value = "pre_delete_time")
    private Date preDeleteTime;

    private Date created;

    private Date modified;
}
