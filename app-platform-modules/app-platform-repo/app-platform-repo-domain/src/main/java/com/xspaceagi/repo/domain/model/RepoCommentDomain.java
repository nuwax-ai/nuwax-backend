package com.xspaceagi.repo.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档评论领域模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoCommentDomain {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 租户ID
     */
    private Long tenantId;

    /**
     * 页面ID
     */
    private Long pageId;

    /**
     * 评论人ID
     */
    private Long userId;

    /**
     * 父评论ID（一层回复），null=顶层评论
     */
    private Long parentId;

    /**
     * 评论富文本JSON
     */
    private String content;

    /**
     * 被提及user_id列表JSON
     */
    private String mentions;

    /**
     * 是否已解决：0未解决 1已解决
     */
    private Integer resolved;

    /**
     * 评论锚点（局部文字评论）：JSON 字符串 { from, to, text, ctxBefore, ctxAfter }，
     * 用于前端定位被评论的文本片段；页面级评论为 null
     */
    private String anchor;

    /**
     * 创建时间
     */
    private Date created;

    /**
     * 修改时间
     */
    private Date modified;
}
