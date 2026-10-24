package com.xspaceagi.userapp.domain.model;

import java.util.Date;
import java.util.List;

import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.userapp.sdk.dto.RedirectUriEntry;
import com.xspaceagi.userapp.sdk.dto.SandboxTypeEnum;
import com.xspaceagi.userapp.sdk.dto.UserAppPublishStatus;


import lombok.Data;

@Data
public class UserProjectModel {

    private Long id;

    private Long tenantId;

    private Long spaceId;

    private Long creatorId;

    private String name;

    private String description;

    private String icon;

    private ProjectTypeEnum projectType;

    /** 查询专用：类型 IN 过滤（不落库） */
    private List<ProjectTypeEnum> projectTypes;

    /** 查询专用：空间 IN 过滤（不落库） */
    private List<Long> spaceIds;

    /** 查询专用：主键 IN 过滤（收藏过滤等，不落库） */
    private List<Long> ids;

    /** 查询专用：引用ID IN 过滤（user_app 主键等，不落库） */
    private List<Long> refIds;

    private Long refId;

    private Long sandboxId;

    private SandboxTypeEnum sandboxType;

    /** 资料库协作目录 slugId */
    private String repoSlugId;

    /** 项目计划表slugId */
    private String planSlugId;

    /** 开发关联智能体ID（仅全栈应用登记写入） */
    private Long devAgentId;

    /** OAuth2 Client ID（平台为三方应用生成，全局唯一） */
    private String oauthClientId;

    /** OAuth2 Client Secret（AES/GCM 密文） */
    private String oauthClientSecret;

    /** 应用主页地址 */
    private String homepageUrl;

    /** OAuth2 主回调地址 */
    private String redirectUri;

    /** OAuth2 补充回调地址（不含主地址；每条带来源标记，生效集合 = 主地址 ∪ 各条目 uri） */
    private List<RedirectUriEntry> redirectUris;

    /** 允许申请的 scope（逗号分隔，为空取平台默认） */
    private String oauthScope;

    /** OAuth2 接入是否启用 */
    private Boolean oauthEnabled;

    /** 发布状态 */
    private UserAppPublishStatus publishStatus;

    /** 置顶标记 */
    private Boolean pinned;

    /** 归档标记 */
    private Boolean archived;

    /** 名称是否已定制 */
    private Boolean nameDefined;

    /** 查询专用：archived 列等值匹配（true=仅归档，false=未归档，null=不过滤） */
    private Boolean archivedEq;

    private Date modified;

    private Date created;
}
