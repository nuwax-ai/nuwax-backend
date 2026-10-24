package com.xspaceagi.repo.web.dto.sidecar;

import lombok.Data;

/**
 * 转发给 Sidecar /internal/export 的请求体（export.ts ExportRequest）。
 */
@Data
public class SidecarExportRequest {

    /** `page:<slugId>` */
    private String documentName;

    /** 导出格式 xlsx/pptx */
    private String format;

    /** 下载文件名（不含扩展名） */
    private String fileName;

    /** 登录态（供 Sidecar 拉取图片/媒体，含图 pptx 导出需要） */
    private String ticket;
}
