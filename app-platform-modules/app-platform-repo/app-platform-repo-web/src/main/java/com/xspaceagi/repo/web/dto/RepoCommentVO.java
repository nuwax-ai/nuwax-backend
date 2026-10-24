package com.xspaceagi.repo.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 文档评论视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoCommentVO {

    private Long id;

    private Long pageId;

    private Long userId;

    /**
     * 评论人昵称（后端批量解析用户表填充，避免前端回落到「用户 N」）
     */
    private String nickName;

    /**
     * 评论人用户名
     */
    private String userName;

    /**
     * 评论人头像
     */
    private String avatar;

    /**
     * 父评论ID，null=顶层评论
     */
    private Long parentId;

    /**
     * 评论富文本JSON
     */
    private String content;

    /**
     * 被提及用户ID列表
     */
    private List<Long> mentions;

    /**
     * 0未解决 1已解决
     */
    private Integer resolved;

    /**
     * 评论锚点 JSON（局部文字评论定位用），页面级评论为 null
     */
    private String anchor;

    private Date created;

    private Date modified;
}
