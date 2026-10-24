package com.xspaceagi.repo.application.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 空间主页分享列表行（修订需求4：接收分享/对外分享 两视图共用）。
 * <p>
 * 接收分享视角填充 sourceSpace*（文档源空间）；对外分享视角填充 targetSpace*（分享去向空间）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoSpaceShareItemVO {

    /**
     * 页面ID
     */
    private Long id;

    /**
     * 分享记录ID（取消分享等场景可用）
     */
    private Long shareId;

    private String slugId;

    private String title;

    /**
     * 页面类型：doc富文本 sheet表格
     */
    private String pageType;

    /**
     * 导入源文件扩展名，可空
     */
    private String sourceExt;

    /**
     * 文档源空间ID（接收分享视角：文档来自哪个空间）
     */
    private Long sourceSpaceId;

    /**
     * 文档源空间名（批量查询填充，查不到为 null）
     */
    private String sourceSpaceName;

    /**
     * 分享目标空间ID（对外分享视角：分享去了哪个空间）
     */
    private Long targetSpaceId;

    /**
     * 分享目标空间名（批量查询填充，查不到为 null）
     */
    private String targetSpaceName;

    /**
     * 分享者名称（昵称优先，无昵称用用户名；查不到为 null）
     */
    private String sharedByName;

    /**
     * 该条分享的权限位组合（RepoConstants.PERM_*）
     */
    private Integer permBits;

    /**
     * 分享时间
     */
    private Date sharedAt;
}
