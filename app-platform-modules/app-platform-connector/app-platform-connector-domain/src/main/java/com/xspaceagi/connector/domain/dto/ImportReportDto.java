package com.xspaceagi.connector.domain.dto;

import lombok.Data;

import java.util.List;

/**
 * 导入 dry-run 报告（设计文档 §4.1：解析→dry-run→diff→人审→apply）。
 */
@Data
public class ImportReportDto {

    /** apply 凭证：会话内唯一，apply 时校验一致性 */
    private String importId;

    private String source;

    private String providerVersion;

    private int addCount;

    private int updateCount;

    private int unchangedCount;

    private int skipProtectedCount;

    private List<ImportItemDto> items;
}
