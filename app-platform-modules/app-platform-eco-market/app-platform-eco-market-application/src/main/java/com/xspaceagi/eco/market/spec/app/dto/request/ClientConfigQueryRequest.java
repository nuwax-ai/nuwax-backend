package com.xspaceagi.eco.market.spec.app.dto.request;

import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.eco.market.domain.dto.req.ServerConfigQueryRequest;
import com.xspaceagi.eco.market.domain.model.valueobj.QueryEcoMarketVo;
import com.xspaceagi.eco.market.spec.enums.EcoMarketSubTabType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 客户端配置查询请求DTO
 */
@Data
@Schema(description = "客户端配置查询请求DTO")
public class ClientConfigQueryRequest {

    /**
     * 名称，模糊查询
     */
    @Schema(description = "名称，模糊查询")
    private String name;

    /**
     * 市场类型,1:插件;2:模板;3:MCP
     */
    @Schema(description = "市场类型,1:插件;2:模板;3:MCP")
    @NotNull(message = "Market type is required")
    private Integer dataType;

    /**
     * tab类型: 1: 全部; 2: 启用的;3:我的分享; 默认全部
     * 
     * @see EcoMarketSubTabType
     */
    @Schema(description = "tab类型: 1: 全部; 2: 启用的;3:我的分享;")
    private Integer subTabType;

    /**
     * 细分类型,比如: 插件,智能体,工作流
     * @see Published.TargetType
     */
    @Schema(description = "类型,比如: 插件,智能体,工作流")
    private String targetType;

    /**
     * 子类型
     * @see Published.TargetSubType
     */
    @Schema(description = "子类型")
    private String targetSubType;

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

    public static QueryEcoMarketVo convertToQueryEcoMarketVo(ClientConfigQueryRequest request) {
        return QueryEcoMarketVo.builder()
                .name(request.getName())
                .dataType(request.getDataType())
                .targetType(request.getTargetType())
                .targetSubType(request.getTargetSubType())
                .subTabType(request.getSubTabType())
                .categoryCode(request.getCategoryCode())
                .shareStatus(request.getShareStatus())
                .useStatus(null)
                .ownedFlag(null)
                .build();
    }


    public static ServerConfigQueryRequest convertToServerConfigQueryRequest(ClientConfigQueryRequest request, Integer subTabType) {
        ServerConfigQueryRequest serverConfigQueryRequest = new ServerConfigQueryRequest();
        serverConfigQueryRequest.setName(request.getName());
        serverConfigQueryRequest.setTargetType(request.getTargetType());
        serverConfigQueryRequest.setTargetSubType(request.getTargetSubType());
        serverConfigQueryRequest.setDataType(request.getDataType());
        serverConfigQueryRequest.setSubTabType(subTabType);
        serverConfigQueryRequest.setCategoryCode(request.getCategoryCode());
        serverConfigQueryRequest.setShareStatus(request.getShareStatus());
        return serverConfigQueryRequest;
    }

}