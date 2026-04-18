package com.xspaceagi.eco.market.domain.dto.req;

import com.xspaceagi.agent.core.adapter.repository.entity.Published;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 服务器配置保存请求DTO
 */
@Data
@Schema(description = "服务器配置保存请求DTO")
public class ServerConfigSaveReqDTO {

    /**
     * ID,新增时为空,更新时必填
     */
    @Schema(description = "ID,新增时为空,更新时必填")
    private Long id;

    @NotBlank(message = "Unique identifier is required")
    @Schema(description = "唯一标识", requiredMode = Schema.RequiredMode.REQUIRED)
    private String uid;

    /**
     * 名称
     */
    @NotBlank(message = "Name is required")
    @Schema(description = "名称", requiredMode = Schema.RequiredMode.REQUIRED)
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
    @Schema(description = "市场类型,默认插件,1:插件;2:模板;3:MCP", requiredMode = Schema.RequiredMode.REQUIRED)
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
     * 配置json,存储插件的配置信息如果有其他额外的信息保存放这里
     */
    @Schema(description = "配置json,存储插件的配置信息如果有其他额外的信息保存放这里")
    private String configJson;

    /**
     * 图标图片地址
     */
    @Schema(description = "图标图片地址")
    private String icon;

    /**
     * 客户端ID
     */
    @NotBlank(message = "Client ID is required")
    @Schema(description = "客户端ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clientId;

    /**
     * 客户端密钥
     */
    @NotBlank(message = "Client secret is required")
    @Schema(description = "客户端密钥", requiredMode = Schema.RequiredMode.REQUIRED)
    private String clientSecret;


    /**
     * 页面压缩包地址
     */
    @Schema(description = "页面压缩包地址")
    private String pageZipUrl;
}