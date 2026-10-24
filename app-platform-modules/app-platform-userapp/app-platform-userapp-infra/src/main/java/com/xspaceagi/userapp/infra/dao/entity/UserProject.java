package com.xspaceagi.userapp.infra.dao.entity;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.type.EnumTypeHandler;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.userapp.infra.dao.typehandler.RedirectUriEntryListTypeHandler;
import com.xspaceagi.userapp.sdk.dto.RedirectUriEntry;
import com.xspaceagi.userapp.sdk.dto.SandboxTypeEnum;
import com.xspaceagi.userapp.sdk.dto.UserAppPublishStatus;

import lombok.Data;

@TableName(value = "user_project", autoResultMap = true)
@Data
public class UserProject {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    private Long spaceId;

    private Long creatorId;

    private String name;

    private String description;

    private String icon;

    @TableField(value = "project_type", typeHandler = EnumTypeHandler.class)
    private ProjectTypeEnum projectType;

    private Long refId;

    private Long sandboxId;

    @TableField(value = "repo_slug_id")
    private String repoSlugId;

    @TableField(value = "plan_slug_id")
    private String planSlugId;

    @TableField(value = "dev_agent_id")
    private Long devAgentId;

    /** OAuth2 Client ID（平台为三方应用生成，全局唯一） */
    @TableField(value = "oauth_client_id")
    private String oauthClientId;

    /** OAuth2 Client Secret（AES/GCM 密文，明文只在生成时返回一次） */
    @TableField(value = "oauth_client_secret")
    private String oauthClientSecret;

    /** 应用主页地址 */
    @TableField(value = "homepage_url")
    private String homepageUrl;

    /** OAuth2 主回调地址（授权码模式精确匹配） */
    @TableField(value = "redirect_uri")
    private String redirectUri;

    /**
     * OAuth2 补充回调地址（JSON 对象数组，不含主地址）。每条自带来源标记，见 {@link RedirectUriEntry}。
     * <p>
     * 生效的回调地址集合 = {redirectUri} ∪ set(各条目的 uri)，授权时任一全等即通过；
     * 集合整体为空才拒绝授权（主地址为空但补充列表有值是合法状态——沙箱里的 AI 只往补充列表写）。
     * <p>
     * 写入是<b>增删</b>而非整表替换：Sandbox 来源只能删 Sandbox 条目，Manual（网页人工配置）不受限。
     */
    @TableField(value = "redirect_uris", typeHandler = RedirectUriEntryListTypeHandler.class)
    private List<RedirectUriEntry> redirectUris;

    /** 允许申请的 scope（逗号分隔，为空取平台默认） */
    @TableField(value = "oauth_scope")
    private String oauthScope;

    /** OAuth2 接入是否启用：true 启用；false 停用（停用后授权请求与令牌校验一律失败） */
    @TableField(value = "oauth_enabled")
    private Boolean oauthEnabled;

    /** 发布状态 */
    @TableField(value = "publish_status", typeHandler = EnumTypeHandler.class)
    private UserAppPublishStatus publishStatus;

    /** 置顶标记：true=置顶（tab 列表排最前） */
    @TableField(value = "pinned")
    private Boolean pinned;

    /** 归档标记：true=归档（tab 列表不可见，pageQuery 可查） */
    @TableField(value = "archived")
    private Boolean archived;

    /** 名称是否已定制：创建显式命名或经改名后置 true；走默认名兜底为 false。 */
    @TableField(value = "name_defined")
    private Boolean nameDefined;

    @TableField(value = "sandbox_type", typeHandler = EnumTypeHandler.class)
    private SandboxTypeEnum sandboxType;

    private Date created;

    private Date modified;
}
