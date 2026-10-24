package com.xspaceagi.repo.web.dto.sidecar;

import lombok.Data;

/**
 * 转发给 Sidecar /internal/import 的请求体（字段与 nuwax-repo-collab import.ts ImportRequest 一致）。
 */
@Data
public class SidecarImportRequest {

    /** 目标空间ID（必填） */
    private Long spaceId;

    /** 父页面ID，null=根节点 */
    private Long parentId;

    /** 标题；缺省 = fileName 去扩展名 */
    private String title;

    /** 原件文件名（含扩展名，决定 sourceExt / pageType / 类型分流），必填 */
    private String fileName;

    /** 已上传的原件 file key（fileKey / content / text 三选一，fileKey 优先） */
    private String fileKey;

    /** 文件内容 base64（三选一） */
    private String content;

    /** 纯文本内容（markdown/csv/代码等，三选一） */
    private String text;

    /** 平台登录态 ticket（Java 侧生成，供 Sidecar 回调平台创建页面/上传原件） */
    private String ticket;
}
