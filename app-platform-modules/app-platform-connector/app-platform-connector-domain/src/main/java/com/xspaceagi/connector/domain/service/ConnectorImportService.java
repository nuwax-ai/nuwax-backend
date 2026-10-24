package com.xspaceagi.connector.domain.service;

import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.connector.domain.dto.ImportReportDto;

import java.util.List;

/**
 * 批量导入管线（设计文档 §4.1：解析→dry-run→diff→人审→单事务批量落库）。
 */
public interface ConnectorImportService {

    /**
     * dry-run：解析导入包，与现有目录 diff，产出报告（不落库）。
     *
     * @param bundle 机器转换产出的导入包 JSON
     */
    ImportReportDto dryRun(Long userId, JSONObject bundle);

    /**
     * 确认执行最近一次 dry-run 的 diff（单事务批量落库，尊重 managed_by=user 保护区）。
     */
    ImportReportDto apply(Long userId, String importId);

    /**
     * 空间侧 dry-run：导入目标为指定空间的自定义目录（managed_by=user），
     * 不落官方目录（space_id=0）。service 已存在于官方目录或其他空间时该项跳过。
     */
    ImportReportDto dryRunToSpace(Long userId, Long tenantId, Long spaceId, JSONObject bundle);

    /**
     * 空间侧确认执行：校验会话归属该空间后单事务落库。
     */
    ImportReportDto applyToSpace(Long userId, Long spaceId, String importId);

    /**
     * 导出（仅元数据，不含凭证；市场快照可直接消费）。
     */
    JSONObject export(String service);

    /**
     * 批量导出（单/多/全量；仅元数据，不含凭证）。管理侧导出走此方法，规避用户侧空间校验。
     */
    JSONObject exportByServices(List<String> services);
}
