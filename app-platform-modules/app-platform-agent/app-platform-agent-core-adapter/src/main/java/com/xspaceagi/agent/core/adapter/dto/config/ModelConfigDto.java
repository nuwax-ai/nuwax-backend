package com.xspaceagi.agent.core.adapter.dto.config;

import com.xspaceagi.agent.core.adapter.dto.CreatorDto;
import com.xspaceagi.agent.core.adapter.repository.entity.ModelConfig;
import com.xspaceagi.agent.core.spec.enums.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Data
public class ModelConfigDto implements Serializable {

    @Schema(description = "模型ID")
    private Long id; // 模型ID

    @Schema(description = "商户ID")
    private Long tenantId; // 商户ID

    @Schema(description = "空间ID")
    private Long spaceId;

    @Schema(description = "空间（团队）名称，查询全部团队空间模型列表时返回")
    private String spaceName;

    @Schema(description = "空间类型")
    private String spaceType;

    @Schema(description = "创建者ID", hidden = true)
    private Long creatorId;

    @Schema(description = "模型生效范围，可选值：Space, Tenant, Global")
    private ModelConfig.ModelScopeEnum scope; // 模型生效范围，可选值：Space, Tenant, Global

    @Schema(description = "模型名称")
    private String name; // 模型名称

    @Schema(description = "模型描述")
    private String description; // 模型描述

    @Schema(description = "模型标签")
    private String tag; // 模型标签

    @Schema(description = "模型标签颜色，如 #FF5733")
    private String tagColor; // 模型标签颜色

    @Schema(description = "计费倍率")
    private String cost; // 计费倍率

    @Schema(description = "模型标识")
    private String model; // 模型标识

    @Schema(description = "模型类型，可选值：Completions, Chat, Edits, Images, Embeddings, Audio, Other")
    private ModelTypeEnum type; // 模型类型，可选值：Completions, Chat, Edits, Images, Embeddings, Audio, Other

    @Schema(description = "提供商ID")
    private String pid;

    @Schema(description = "供应商图标")
    private String providerIcon;

    @Schema(description = "提供商名称")
    private String providerName;

    @Schema(description = "模型能力类型列表")
    private List<ModelCapabilityEnum> types;

    private Integer isReasonModel;

    @Schema(description = "网络类型，可选值：Internet 公网; Intranet 内网")
    private ModelConfig.NetworkType networkType;

    @Schema(description = "穿透信息", hidden = true)
    private List<NatInfo> natInfoList;

    @Schema(description = "函数调用支持程度，可选值：Unsupported, CallSupported, StreamCallSupported")
    private ModelFunctionCallEnum functionCall; // 函数调用支持程度，可选值：Unsupported, CallSupported, StreamCallSupported

    @Schema(description = "生成随机性;0-1", hidden = true)
    private Double temperature;

    @Schema(description = "累计概率: 模型在生成输出时会从概率最高的词汇开始选择;0-1", hidden = true)
    private Double topP;

    @Schema(description = "token上限")
    private Integer maxTokens; // token上限

    @Schema(description = "上下文token上限")
    private Integer maxContextTokens; // 上下文token上限

    @Schema(description = "模型接口协议，可选值：OpenAI, Ollama")
    private ModelApiProtocolEnum apiProtocol; // 模型接口协议，可选值：OpenAI, Ollama

    @Schema(description = "API列表")
    private List<ApiInfo> apiInfoList; // API列表 [{"url":"","key":"","weight":1}]

    @Schema(description = "接口调用策略，可选值：RoundRobin, WeightedRoundRobin, LeastConnections, WeightedLeastConnections, Random, ResponseTime")
    private ModelConfig.ModelStrategyEnum strategy; // 接口调用策略，可选值：RoundRobin, WeightedRoundRobin, LeastConnections, WeightedLeastConnections, Random, ResponseTime

    @Schema(description = "向量维度")
    private Integer dimension; // 向量维度

    @Schema(description = "修改时间")
    private Date modified; // 修改时间

    @Schema(description = "创建时间")
    private Date created; // 创建时间

    @Schema(description = "创建者信息")
    private CreatorDto creator;

    @Schema(description = "是否启用")
    private Integer enabled;

    @Schema(description = "访问控制，可选值：0-不管控，1-管控")
    private Integer accessControl;

    @Schema(description = "可使用的业务场景")
    private List<UsageScenarioEnum> usageScenarios;

    @Schema(description = "价格信息")
    private Object pricing;

    @Data
    public static class ApiInfo {
        @Schema(description = "接口地址")
        private String url;

        @Schema(description = "接口密钥")
        private String key;

        @Schema(description = "权重")
        private Integer weight;

        @Schema(description = "0 关闭，空和1为 开启")
        private Integer useProxy;

        public Integer getWeight() {
            return weight == null ? 1 : weight;
        }
    }


    @Data
    public static class NatInfo {

        //模型接口地址
        private String modelApiUrl;

        //穿透客户端key
        private String natClientKey;

        //穿透配置ID
        private String natConfigId;

        //穿透主机
        private String natHost;

        //穿透端口
        private Integer natPort;
    }
}
