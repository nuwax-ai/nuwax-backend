package com.xspaceagi.memory.app.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.memory.app.service.MemoryApplicationService;
import com.xspaceagi.memory.app.service.MemoryUnitService;
import com.xspaceagi.memory.sdk.dto.MemoryMetaData;
import com.xspaceagi.memory.sdk.dto.MemorySaveDto;
import com.xspaceagi.memory.sdk.dto.MemoryUnitDTO;
import com.xspaceagi.memory.sdk.dto.MemoryUnitQueryDTO;
import com.xspaceagi.memory.sdk.service.IMemoryRpcService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class MemoryRpcServiceImpl implements IMemoryRpcService {

    /**
     * update_time 展示格式：Date.toString() 的英文格式（Fri Jun 12 ... CST 2026）对中文场景不直观。
     * DateTimeFormatter 线程安全，可作静态常量复用。
     */
    private static final DateTimeFormatter UPDATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private MemoryApplicationService memoryApplicationService;

    @Resource
    private MemoryUnitService memoryUnitService;

    @Override
    public void createMemory(MemoryMetaData memoryData) {
        try {
            log.debug("createMemory: {}", memoryData);
            memoryApplicationService.createMemory(memoryData);
        } catch (Exception e) {
            log.error("createMemory error: {}", e.getMessage(), e);
            // 由异常处理接管
        }
    }

    @Override
    public String searchMemoriesMd(Long tenantId, Long userId, Long agentId, String inputMessage, String context, boolean justKeywordSearch, boolean filterSensitive) {
        log.debug("searchMemoriesMd: {}, {}, {}, {}, {}", tenantId, userId, inputMessage, context, justKeywordSearch);
        List<MemoryUnitDTO> memoryUnitDTOS = memoryApplicationService.searchMemories(tenantId, userId, agentId, inputMessage, context, justKeywordSearch);
        return toMd(memoryUnitDTOS, filterSensitive);
    }

    private String toMd(List<MemoryUnitDTO> memoryUnitDTOS, boolean filterSensitive) {
        // 两级聚合：category 只打印一次，其下各 subCategory 一组；组内多条记忆用宽表输出
        // （行=记忆，列=该组字段并集 + memory_id + update_time）——同类别记忆逐列对比更利于
        // 模型解析，同时消除"标题每条记忆重复打印"的 token 浪费。
        Map<String, Map<String, List<MemoryUnitDTO>>> groups = new LinkedHashMap<>();
        for (MemoryUnitDTO dto : memoryUnitDTOS) {
            if (filterSensitive && Boolean.TRUE.equals(dto.getIsSensitive())) {
                continue;
            }
            String category = String.valueOf(dto.getCategory());
            String subCategory = String.valueOf(dto.getSubCategory());
            groups.computeIfAbsent(category, k -> new LinkedHashMap<>())
                    .computeIfAbsent(subCategory, k -> new ArrayList<>()).add(dto);
        }
        if (groups.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        groups.forEach((category, subGroups) -> {
            sb.append("# ").append(category).append("\n");
            subGroups.forEach((subCategory, group) -> {
                sb.append("## ").append(subCategory).append("\n");
                appendMdTable(sb, group);
                sb.append("\n");
            });
        });
        return sb.toString();
    }

    /**
     * 组内宽表：行=记忆，列=字段并集（保序）+ memory_id + update_time。
     */
    private void appendMdTable(StringBuilder sb, List<MemoryUnitDTO> group) {
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        List<JSONObject> kvList = new ArrayList<>(group.size());
        for (MemoryUnitDTO dto : group) {
            JSONObject keyValues = parseKeyValues(dto);
            kvList.add(keyValues);
            if (keyValues != null) {
                columns.addAll(keyValues.keySet());
            }
        }
        columns.add("memory_id");
        columns.add("update_time");
        sb.append("| ").append(String.join(" | ", columns)).append(" |\n");
        sb.append("| ").append(String.join(" | ", Collections.nCopies(columns.size(), "---"))).append(" |\n");
        for (int i = 0; i < group.size(); i++) {
            MemoryUnitDTO dto = group.get(i);
            JSONObject keyValues = kvList.get(i);
            List<String> row = new ArrayList<>(columns.size());
            for (String column : columns) {
                if ("memory_id".equals(column)) {
                    row.add(String.valueOf(dto.getId()));
                } else if ("update_time".equals(column)) {
                    row.add(dto.getModified() == null ? "" : formatUpdateTime(dto.getModified()));
                } else {
                    row.add(keyValues != null && keyValues.containsKey(column)
                            ? escapeMdCell(String.valueOf(keyValues.get(column)))
                            : "");
                }
            }
            sb.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
    }

    /**
     * 防御 contentJson 缺失/损坏：返回 null，表格该行仅 memory_id/update_time 有值。
     */
    private JSONObject parseKeyValues(MemoryUnitDTO dto) {
        if (dto.getContentJson() == null || dto.getContentJson().isEmpty()) {
            return null;
        }
        try {
            JSONObject jsonObject = JSONObject.parseObject(dto.getContentJson());
            return jsonObject == null ? null : jsonObject.getJSONObject("keyValues");
        } catch (Exception e) {
            log.warn("[toMd] parse contentJson failed, memoryId={}: {}", dto.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 表格单元格转义：竖线转义、换行折叠为空格，避免破坏表格结构。
     */
    private String escapeMdCell(String value) {
        return value.replace("|", "\\|").replace("\r\n", " ").replace("\n", " ");
    }

    /**
     * update_time 格式化为 yyyy-MM-dd HH:mm:ss（系统时区，与 Date.toString 的 CST 语义一致）。
     */
    private String formatUpdateTime(Date modified) {
        return modified.toInstant().atZone(ZoneId.systemDefault()).format(UPDATE_TIME_FORMATTER);
    }

    @Override
    public String queryMemoriesMd(MemoryUnitQueryDTO memoryUnitQueryDTO, boolean filterSensitive) {
        List<MemoryUnitDTO> memoryUnitDTOS = memoryUnitService.queryList(memoryUnitQueryDTO);
        return toMd(memoryUnitDTOS, filterSensitive);
    }

    @Override
    public void saveMemories(Long tenantId, Long userId, Long agentId, MemorySaveDto memorySaveDto) {
        memoryApplicationService.saveMemories(tenantId, userId, agentId, memorySaveDto);
    }

    @Override
    public void deleteMemories(Long tenantId, Long userId, List<Long> memoryIds) {
        memoryApplicationService.deleteMemories(tenantId, userId, memoryIds);
    }
}
