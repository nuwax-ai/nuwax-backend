package com.xspaceagi.repo.application.service;

import com.xspaceagi.repo.application.service.dto.RepoCommentThread;
import com.xspaceagi.repo.domain.model.RepoCommentDomain;

import java.util.List;

/**
 * 文档评论应用服务。
 * <p>
 * 读写要求可查看（空间成员/被分享/有效分享链接）；发评论另需可编辑或评论权限（需求5）；
 * 解决/删除限评论作者或空间管理员/属主（对齐页面删除规则）。
 * @提及通过 NotifyMessageApplicationService 发送 Private 站内通知。
 * <p>
 * shareToken：分享链接上下文（可空）。登录用户经分享链接看页面时可能非空间成员，
 * 评论读写需带 token 走链接裁决（补查看 + allow_comment 评论位），否则按常规空间/分享权限裁决。
 */
public interface RepoCommentApplicationService {

    /**
     * 查询页面评论线程列表（顶层评论 + 一层回复）
     *
     * @param pageId     页面ID
     * @param shareToken 分享链接 token，可空
     * @return 评论线程列表（按顶层评论 id 升序）
     */
    List<RepoCommentThread> listCommentThreads(Long pageId, String shareToken);

    /**
     * 发表评论或回复（回复仅支持一层：父评论必须是顶层评论）
     *
     * @param pageId         页面ID
     * @param parentId       父评论ID，null=顶层评论
     * @param content        评论富文本JSON
     * @param mentionUserIds 被提及用户ID列表，可空
     * @param anchor         评论锚点 JSON（局部文字评论定位用），页面级评论传 null
     * @param shareToken     分享链接 token，可空
     * @return 新评论领域模型
     */
    RepoCommentDomain addComment(Long pageId, Long parentId, String content, List<Long> mentionUserIds, String anchor, String shareToken);

    /**
     * 标记评论已解决（限评论作者或空间管理员/属主）
     *
     * @param commentId  评论ID
     * @param shareToken 分享链接 token，可空（分享链接观众解决自己评论时用）
     * @return 更新后的评论领域模型
     */
    RepoCommentDomain resolveComment(Long commentId, String shareToken);

    /**
     * 软删除评论及其回复（限评论作者或空间管理员/属主）
     *
     * @param commentId  评论ID
     * @param shareToken 分享链接 token，可空（分享链接观众删除自己评论时用）
     */
    void deleteComment(Long commentId, String shareToken);
}
