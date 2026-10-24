package com.xspaceagi.repo.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @ 提及候选人精简视图。
 * <p>
 * {@code source} 决定前端是否展示「联系人」标记，也解释了权限差异：
 * 空间成员本来就能打开文档，联系人则会在选中后由后端补一份「可查看 + 可评论」的个人分享。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoMentionUserVO {

    /** 用户ID（写进 mention 节点的 attrs.id） */
    private Long userId;

    /** 平台账号名（联系人可能为 null） */
    private String userName;

    /** 展示名：昵称 / 备注名 / 账号名兜底 */
    private String nickName;

    /** 头像 URL（可能为空） */
    private String avatar;

    /** 来源：space=空间成员，contact=IM 联系人 */
    private String source;

    /** 是否属于当前文档所在空间（排序与「无需额外授权」判据） */
    private Boolean inCurrentSpace;

    /** 命中的空间ID（联系人/多空间命中以首个为准，前端不展示） */
    private Long spaceId;
}