package com.xspaceagi.memory.app.service;

import com.xspaceagi.memory.app.service.dto.MemoryExtractItemDto;
import com.xspaceagi.memory.sdk.dto.MemoryMetaData;
import com.xspaceagi.memory.sdk.dto.MemorySaveDto;
import com.xspaceagi.memory.sdk.dto.MemoryUnitDTO;

import java.util.List;

/**
 * 记忆应用服务接口
 */
public interface MemoryApplicationService {

    /**
     * 创建记忆
     */
    List<MemoryExtractItemDto> createMemory(MemoryMetaData memoryData);

    /**
     * 保存记忆
     */
    void saveMemories(Long tenantId, Long userId, Long agentId, MemorySaveDto memorySaveDto);

    /**
     * 搜索记忆
     */
    List<MemoryUnitDTO> searchMemories(Long tenantId, Long userId, Long agentId, String userMessage, String context, boolean justKeywordSearch);

    void deleteMemories(Long tenantId, Long userId, List<Long> memoryIds);
}
