package com.xspaceagi.repo.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档分享链接（需求4：只读，支持登录可访问/任何人可访问）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoShareLinkDomain {

    private Long id;

    private Long tenantId;

    private Long pageId;

    /**
     * 链接 token（对外唯一凭据）
     */
    private String token;

    /**
     * 访问方式：login=登录可访问，public=任何人可访问（含匿名）
     */
    private String accessType;

    /**
     * 是否允许评论（需求5）：1=允许 0=不允许；默认 1
     */
    private Integer allowComment;

    /**
     * 链接授予权限位（本轮需求4：只读类，后端强制去编辑位；缺省仅查看）
     */
    private Integer permBits;

    private Long createdBy;

    private Date created;

    private Date modified;
}
