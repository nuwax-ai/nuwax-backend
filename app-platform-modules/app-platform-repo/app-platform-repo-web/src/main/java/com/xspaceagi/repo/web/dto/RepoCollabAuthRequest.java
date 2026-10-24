package com.xspaceagi.repo.web.dto;

import lombok.Data;

/**
 * Sidecar 鉴权回调请求体。
 */
@Data
public class RepoCollabAuthRequest {

    /**
     * 前端携带的平台 JWT
     */
    private String token;

    /**
     * 页面短链ID（documentName 约定 page:&lt;slugId&gt;）
     */
    private String slugId;

    /**
     * 分享链接 token（可选）：纯链接授权用户（非源空间成员）经此裁决可访问/可编辑；
     * 缺失时按源空间成员身份校验，非成员仍会 403。
     */
    private String shareToken;
}
