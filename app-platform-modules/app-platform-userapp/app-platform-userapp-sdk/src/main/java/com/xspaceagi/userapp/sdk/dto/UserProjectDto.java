package com.xspaceagi.userapp.sdk.dto;

import java.io.Serializable;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import java.util.Date;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用户项目（跨类型统一登记）")
public class UserProjectDto implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "统一登记表主键（user_project.id）；支付开放接口的 appId 用它，勿与业务 projectId 混淆")
    private Long dbId;

    @Schema(description = "商户ID")
    private Long tenantId;

    @Schema(description = "空间ID")
    private Long spaceId;

    @Schema(description = "创建人用户ID")
    private Long creatorId;

    @Schema(description = "项目名称")
    private String name;

    @Schema(description = "项目描述")
    private String description;

    @Schema(description = "项目图标")
    private String icon;

    @Schema(description = "项目类型")
    private ProjectTypeEnum projectType;

    @Schema(description = "项目ID")
    private Long projectId;

    @Schema(description = "沙箱ID")
    private Long sandboxId;

    @Schema(description = "沙箱类型")
    private SandboxTypeEnum sandboxType;

    @Schema(description = "当前用户的项目默认绑定文件工作目录（自定义目录有值）")
    private String fileWorkspacePath;

    @Schema(description = "当前用户的项目默认绑定 agent 执行路径（自定义目录有值）")
    private String agentWorkspacePath;

    @Schema(description = "开发关联智能体ID（仅全栈应用有值；常规项目为空）")
    private Long devAgentId;

    @Schema(description = "资料库目录ID")
    private String repoSlugId;

    @Schema(description = "项目计划表ID")
    private String planSlugId;

    @Schema(description = "OAuth2 Client ID（仅三方应用有值；密钥不下发，需走设置页专用接口）")
    private String oauthClientId;

    @Schema(description = "应用主页地址")
    private String homepageUrl;

    @Schema(description = "OAuth2 主回调地址")
    private String redirectUri;

    @Schema(description = "OAuth2 补充回调地址（不含主地址；每条带来源标记，生效集合 = 主地址 ∪ 各条目 uri）")
    private List<RedirectUriEntry> redirectUris;

    @Schema(description = "OAuth2 接入是否启用")
    private Boolean oauthEnabled;

    @Schema(description = "发布状态")
    private UserAppPublishStatus publishStatus;

    @Schema(description = "置顶标记")
    private Boolean pinned;

    @Schema(description = "归档标记")
    private Boolean archived;

    @Schema(description = "名称是否已定制")
    private Boolean nameDefined;

    @Schema(description = "当前用户收藏标记")
    private Boolean collected;

    @Schema(description = "当前用户是否项目创建者")
    private Boolean owner;

    @Schema(description = "更新时间")
    private Date modified;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "开发智能体的关联的会话ID（不落库，传输用）")
    private Long conversationId;
}
