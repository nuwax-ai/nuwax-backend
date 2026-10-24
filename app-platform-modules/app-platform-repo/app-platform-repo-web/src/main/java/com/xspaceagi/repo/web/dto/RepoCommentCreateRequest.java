package com.xspaceagi.repo.web.dto;

import lombok.Data;

import java.util.List;

/**
 * 发表评论/回复请求
 */
@Data
public class RepoCommentCreateRequest {

    /**
     * 父评论ID，null=顶层评论；仅支持一层回复
     */
    private Long parentId;

    /**
     * 评论富文本JSON
     */
    private String content;

    /**
     * 被提及用户ID列表（仅同空间成员会收到通知）
     */
    private List<Long> mentions;

    /**
     * 评论锚点（局部文字评论）：JSON 字符串 { from, to, text, ctxBefore, ctxAfter }；
     * 页面级评论（不锚定文本）不传或传 null。仅透传不校验（与 content 同策略）
     */
    private String anchor;
}
