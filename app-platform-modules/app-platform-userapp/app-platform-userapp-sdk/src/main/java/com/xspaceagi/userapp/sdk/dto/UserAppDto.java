package com.xspaceagi.userapp.sdk.dto;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用户自定义应用")
public class UserAppDto implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "应用ID（项目主键 id，即 app_id）")
    private Long id;

    @Schema(description = "商户ID")
    private Long tenantId;

    @Schema(description = "空间ID")
    private Long spaceId;

    @Schema(description = "创建人用户ID")
    private Long creatorId;

    @Schema(description = "应用名称")
    private String name;

    /** 名称是否已定制（false=默认名兜底创建，前端可触发自动命名；取自统一登记表） */
    private Boolean nameDefined;

    @Schema(description = "应用描述")
    private String description;

    @Schema(description = "应用图标")
    private String icon;

    @Schema(description = "封面图片")
    private String coverImg;

    @Schema(description = "沙箱ID")
    private Long sandboxId;

    @Schema(description = "沙箱类型")
    private SandboxTypeEnum sandboxType;

    @Schema(description = "当前用户的项目默认绑定文件工作目录（自定义目录有值）")
    private String fileWorkspacePath;

    @Schema(description = "当前用户的项目默认绑定 agent 执行路径（自定义目录有值）")
    private String agentWorkspacePath;

    @Schema(description = "发布部署目标：platform 平台部署/private 私服部署")
    private String deployType;

    @Schema(description = "部署目标服务器ID")
    private Long deployServerId;

    @Schema(description = "资料库协作目录ID")
    private String repoSlugId;

    @Schema(description = "项目计划多维表格ID")
    private String planSlugId;

    @Schema(description = "开发关联智能体ID")
    private Long devAgentId;

    @Schema(description = "生产部署标记")
    private Boolean prodDeployed;

    @Schema(description = "生产运行状态：true=运行中，false=已停止；仅 prodDeployed=true 时有意义（从未部署的应用恒为 false，不代表已停止）")
    private Boolean prodRunning;

    @Schema(description = "当前生产部署的版本号")
    private String prodReleaseId;

    @Schema(description = "发布状态")
    private UserAppPublishStatus publishStatus;

    @Schema(description = "构建版本记录")
    private List<BuildVersionDto> buildVersions;

    @Schema(description = "开发环境数据库账号")
    private String devDbUsername;

    @Schema(description = "开发环境数据库密码是否已设置")
    private Boolean devDbPasswordSet;

    @Schema(description = "生产环境数据库账号")
    private String prodDbUsername;

    @Schema(description = "生产环境数据库密码是否已设置")
    private Boolean prodDbPasswordSet;

    @Schema(description = "更新时间")
    private Date modified;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "当前用户是否项目创建者（creatorId 即登录用户）")
    private Boolean owner;

    @Schema(description = "开发智能体的关联的会话ID（不落库，传输用）")
    private Long conversationId;
}
