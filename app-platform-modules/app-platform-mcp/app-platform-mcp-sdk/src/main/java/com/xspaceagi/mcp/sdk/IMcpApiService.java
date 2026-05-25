package com.xspaceagi.mcp.sdk;

import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.mcp.sdk.dto.McpExecuteOutput;
import com.xspaceagi.mcp.sdk.dto.McpExecuteRequest;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import reactor.core.publisher.Flux;

public interface IMcpApiService {

    /**
     * 获取已部署的MCP详情
     *
     * @param id
     * @param spaceId 可不传，传了会根据空间过滤
     * @return
     */
    McpDto getDeployedMcp(Long id, Long spaceId);

    Flux<McpExecuteOutput> execute(McpExecuteRequest mcpExecuteRequestDto);

    Long addAndDeployMcp(Long userId, Long spaceId, McpDto mcpDto);


    //生态市场使用
    Long deployOfficialMcp(McpDto mcpDto);

    void stopOfficialMcp(Long id);

    Long deployProxyMcp(McpDto mcpDto);

    String getExportMcpServerConfig(Long userId, Long mcpId, UserAccessKeyDto.UserAccessKeyConfig userAccessKeyConfig);

    /**
     * 统计 MCP 总数
     *
     * @return MCP 总数
     */
    Long countTotalMcps();

    /**
     * 管理端查询MCP列表
     *
     * @param pageNo 页码
     * @param pageSize 每页大小
     * @param name 名称模糊搜索
     * @param creatorIds 创建人ID列表
     * @param spaceId 空间ID
     * @return MCP分页数据
     */
    com.baomidou.mybatisplus.core.metadata.IPage<McpDto> queryListForManage(Integer pageNo, Integer pageSize, String name, java.util.List<Long> creatorIds, Long spaceId);

    /**
     * 管理端删除MCP
     *
     * @param id MCP ID
     */
    void deleteForManage(Long id);
}
