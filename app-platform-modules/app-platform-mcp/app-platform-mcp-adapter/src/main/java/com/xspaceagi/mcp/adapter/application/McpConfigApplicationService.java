package com.xspaceagi.mcp.adapter.application;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.mcp.adapter.dto.McpPageQueryDto;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;

import java.util.List;
import java.util.Map;

public interface McpConfigApplicationService {

    void addMcp(McpDto mcpDto);

    void updateMcp(McpDto mcpDto);

    void deleteMcp(Long id);

    McpDto getMcp(Long id);

    McpDto getDeployedMcp(Long id);

    List<McpDto> queryMcpListBySpaceId(Long spaceId);

    IPage<McpDto> queryDeployedMcpList(McpPageQueryDto mcpPageQueryDto);

    IPage<McpDto> queryDeployedMcpListForManage(McpPageQueryDto mcpPageQueryDto);

    String getExportMcpServerConfig(Long userId, Long mcpId, UserAccessKeyDto.UserAccessKeyConfig userAccessKeyConfig);

    String refreshExportMcpServerConfig(Long userId, Long mcpId);

    //生态市场使用
    Long deployOfficialMcp(McpDto mcpDto);

    Long deployProxyMcp(McpDto mcpDto);

    void stopOfficialMcp(Long id);

    /**
     * 统计 MCP 总数
     *
     * @return MCP 总数
     */
    Long countTotalMcps();
}
