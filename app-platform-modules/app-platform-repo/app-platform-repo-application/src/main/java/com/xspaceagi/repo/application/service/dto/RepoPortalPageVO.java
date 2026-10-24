package com.xspaceagi.repo.application.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 门户列表行（需求1：最近访问/我分享的/与我共享 三标签页共用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPortalPageVO {

    private Long id;

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

    private Long spaceId;

    /**
     * 文档所有者（创建者）ID
     */
    private Long ownerId;

    /**
     * 文档所有者名称（批量查询填充，查不到为 null）
     */
    private String ownerName;

    /**
     * 当前用户是否为该文档创建者（需求：我分享的列表仅创建者可见「清空分享」按钮）
     */
    private Boolean creator;

    /**
     * 位置类型：personal=我的资料（个人空间）；space=空间名；shared=与我共享
     */
    private String locationType;

    /**
     * 位置展示名：个人空间=「我的资料」；空间=空间名；被分享=「与我共享」
     */
    private String spaceName;

    /**
     * 列表排序与展示时间：最近访问=访问/编辑较新者；分享视角=分享时间
     */
    private Date time;

    /**
     * 正文快照字符数（SQL 侧 CHAR_LENGTH(html_snapshot) 预计算），列表「大小」列 = contentSize + attachmentSize。
     */
    private Long contentSize;

    /**
     * 正文媒体字节数（media_size 列，快照回写时预计算），与 {@link RepoPageVO#getAttachmentSize()} 同源。
     */
    private Long attachmentSize;
}
