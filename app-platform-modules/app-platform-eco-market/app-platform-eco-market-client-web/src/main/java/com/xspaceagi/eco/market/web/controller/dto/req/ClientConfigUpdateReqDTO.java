package com.xspaceagi.eco.market.web.controller.dto.req;

import java.time.LocalDateTime;

import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.eco.market.domain.model.EcoMarketClientConfigModel;
import com.xspaceagi.eco.market.spec.enums.EcoMarketOwnedFlagEnum;
import com.xspaceagi.eco.market.spec.enums.EcoMarketShareStatusEnum;
import com.xspaceagi.eco.market.spec.enums.EcoMarketUseStatusEnum;
import com.xspaceagi.system.spec.common.UserContext;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 客户端配置更新请求DTO
 */
@Getter
@Setter
@Schema(description = "客户端配置更新请求DTO")
public class ClientConfigUpdateReqDTO {

    /**
     * 配置UID
     */
    @NotBlank(message = "Configuration UID is required")
    @Schema(description = "配置UID", required = true)
    private String uid;
    /**
     * 名称
     */
    @NotBlank(message = "Name is required")
    @Schema(description = "名称", requiredMode = RequiredMode.REQUIRED)
    private String name;

    /**
     * 描述
     */
    @Schema(description = "描述")
    private String description;

    /**
     * 市场类型,默认插件,1:插件;2:模板;3:MCP
     */
    @NotNull(message = "Market type is required")
    @Schema(description = "市场类型,1:插件;2:模板;3:MCP", requiredMode = RequiredMode.REQUIRED)
    private Integer dataType;

    /**
     * 细分类型,比如: 插件,智能体,工作流
     */
    @Schema(description = "细分类型,比如: 插件,智能体,工作流")
    private String targetType;

    /**
     * 子类型
     * @see Published.TargetSubType
     */
    @Schema(description = "子类型")
    private String targetSubType;

    /**
     * 具体目标的id,可以智能体,工作流,插件,还有mcp等
     */
    @Schema(description = "具体目标的id,可以智能体,工作流,插件,还有mcp等")
    private Long targetId;

    /**
     * 分类编码,商业服务等,通过接口获取
     */
    @Schema(description = "分类编码,商业服务等,通过接口获取")
    private String categoryCode;

    /**
     * 分类名称,商业服务等,通过接口获取
     */
    @Schema(description = "分类名称,商业服务等,通过接口获取")
    private String categoryName;

    /**
     * 使用状态,1:启用;2:禁用;
     */
    @Schema(description = "使用状态,1:启用;2:禁用;")
    private Integer useStatus;

    /**
     * 作者信息
     */
    @Schema(description = "作者信息")
    private String author;

    /**
     * 发布文档
     */
    @Schema(description = "发布文档")
    private String publishDoc;

    /**
     * 请求参数配置json
     */
    @Schema(description = "请求参数配置json")
    private String configParamJson;

    /**
     * 图标图片地址
     */
    @Schema(description = "图标图片地址")
    private String icon;

    public static EcoMarketClientConfigModel convert2Dto(ClientConfigUpdateReqDTO reqDTO, UserContext userContext) {

        var currentTime = LocalDateTime.now();
        EcoMarketClientConfigModel ecoMarketClientConfigModel = EcoMarketClientConfigModel.builder()
                .uid(reqDTO.getUid())
                .name(reqDTO.getName())
                .description(reqDTO.getDescription())
                .dataType(reqDTO.getDataType())
                .targetType(reqDTO.getTargetType())
                .targetSubType(reqDTO.getTargetSubType())
                .targetId(reqDTO.getTargetId())
                .categoryCode(reqDTO.getCategoryCode())
                .categoryName(reqDTO.getCategoryName())
                .ownedFlag(EcoMarketOwnedFlagEnum.YES.getCode())
                .shareStatus(EcoMarketShareStatusEnum.DRAFT.getCode())
                .useStatus(EcoMarketUseStatusEnum.ENABLED.getCode())
                .publishTime(currentTime)
                .offlineTime(null)
                .versionNumber(1L)
                .author(reqDTO.getAuthor())
                .publishDoc(reqDTO.getPublishDoc())
                .configParamJson(reqDTO.getConfigParamJson())
                .configJson(null)
                .icon(reqDTO.getIcon())
                .tenantId(userContext.getTenantId())
                .createClientId(null)
                .created(currentTime)
                .creatorId(userContext.getUserId())
                .creatorName(userContext.getUserName())
                .modified(currentTime)
                .modifiedId(null)
                .modifiedName(null)
                .isNewVersion(null)
                .serverVersionNumber(null)
                .build();
        return ecoMarketClientConfigModel;

    }
}