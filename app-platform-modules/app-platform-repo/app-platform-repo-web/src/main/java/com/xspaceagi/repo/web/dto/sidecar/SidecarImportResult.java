package com.xspaceagi.repo.web.dto.sidecar;

import lombok.Data;

/**
 * Sidecar /internal/import 返回（nuwax-repo-collab import.ts ImportResult）。
 */
@Data
public class SidecarImportResult {

    private boolean ok;

    private Long pageId;

    private String slugId;

    private String title;

    private String pageType;

    private String sourceExt;

    private String originalFileKey;

    private boolean parsed;

    /** 失败时的错误信息（ok=false） */
    private String error;
}
