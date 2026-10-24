package com.xspaceagi.connector.domain.dto;

import lombok.Data;

/**
 * 导入 dry-run diff 单条。
 */
@Data
public class ImportItemDto {

    /** provider / action */
    private String type;

    private String service;

    private String actionKey;

    /** ImportOpEnum code：add/update/unchanged/skip_protected */
    private String op;

    /** 说明（跳过原因等） */
    private String reason;
}
