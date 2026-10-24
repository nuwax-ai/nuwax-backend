package com.xspaceagi.repo.web.dto;

import com.xspaceagi.repo.domain.model.RepoShareLinkDomain;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 分享链接视图（需求4）：前端直接展示/复制。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoShareLinkVO {

    private Long id;

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
     * 是否允许评论（需求5）：1=允许 0=不允许
     */
    private Integer allowComment;

    /**
     * 链接授予权限位（本轮需求4：只读类）
     */
    private Integer permBits;

    private Long createdBy;

    private Date created;

    public static RepoShareLinkVO from(RepoShareLinkDomain domain) {
        if (domain == null) {
            return null;
        }
        return RepoShareLinkVO.builder()
                .id(domain.getId())
                .pageId(domain.getPageId())
                .token(domain.getToken())
                .accessType(domain.getAccessType())
                .allowComment(domain.getAllowComment())
                .permBits(domain.getPermBits())
                .createdBy(domain.getCreatedBy())
                .created(domain.getCreated())
                .build();
    }
}
