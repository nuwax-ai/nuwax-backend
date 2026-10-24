package com.xspaceagi.repo.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

/**
 * 文档评论实体
 * @TableName repo_comment
 */
@TableName(value = "repo_comment")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RepoComment {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户ID
     */
    @TableField(value = "_tenant_id")
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
     * 被提及user_id列表JSON（用于发送 NotifyMessage 站内通知）
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
     * 软删除标记：1有效 0删除
     */
    private Integer yn;

    /**
     * 创建时间
     */
    private Date created;

    /**
     * 修改时间
     */
    private Date modified;
}
