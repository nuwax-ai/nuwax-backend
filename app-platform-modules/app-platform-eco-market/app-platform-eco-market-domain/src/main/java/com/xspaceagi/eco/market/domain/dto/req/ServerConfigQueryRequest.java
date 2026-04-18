package com.xspaceagi.eco.market.domain.dto.req;

import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 服务器配置查询请求DTO
 */
@Data
@Schema(description = "服务器配置查询请求DTO")
public class ServerConfigQueryRequest {

    /**
     * 名称，模糊查询
     */
    @Schema(description = "名称，模糊查询")
    private String name;


    /**
     * 细分类型,比如: 插件,智能体,工作流
     * @see Published.TargetType
     */
    private String targetType;

    /**
     * 子类型
     * @see Published.TargetSubType
     */
    private String targetSubType;

    /**
     * 市场类型,1:插件;2:模板;3:MCP
     */
    @Schema(description = "市场类型,1:插件;2:模板;3:MCP")
    @NotNull(message = "Market type is required")
    private Integer dataType;

    /**
     * tab类型: 1: 全部; 2: 启用的;3:我的分享; 默认全部
     * @see EcoMarketSubTabType
     */
    @Schema(description = "tab类型: 1: 全部; 2: 启用的;3:我的分享;")
    private Integer subTabType;


    /**
     * 分类编码
     */
    @Schema(description = "分类编码")
    private String categoryCode;

    /**
     * 分享状态,1:草稿;2:审核中;3:已发布;4:已下线;5:驳回
     */
    @Schema(description = "分享状态,1:草稿;2:审核中;3:已发布;4:已下线;5:驳回")
    private Integer shareStatus;
}