package com.xspaceagi.agent.core.adapter.dto.config;

import com.xspaceagi.agent.core.adapter.dto.CreatorDto;
import com.xspaceagi.agent.core.adapter.repository.entity.ModelConfig;
import com.xspaceagi.agent.core.spec.enums.ModelApiProtocolEnum;
import com.xspaceagi.agent.core.spec.enums.ModelFunctionCallEnum;
import com.xspaceagi.agent.core.spec.enums.ModelTypeEnum;
import com.xspaceagi.agent.core.spec.enums.UsageScenarioEnum;
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

    @Schema(description = "创建者ID", hidden = true)
    private Long creatorId;

    @Schema(description = "模型生效范围，可选值：Space, Tenant, Global")
    private ModelConfig.ModelScopeEnum scope; // 模型生效范围，可选值：Space, Tenant, Global

    @Schema(description = "模型名称")
    private String name; // 模型名称

    @Schema(description = "模型描述")
    private String description; // 模型描述

    @Schema(description = "模型标识")
    private String model; // 模型标识

    @Schema(description = "模型类型，可选值：Completions, Chat, Edits, Images, Embeddings, Audio, Other")
    private ModelTypeEnum type; // 模型类型，可选值：Completions, Chat, Edits, Images, Embeddings, Audio, Other

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

    @Data
    public static class ApiInfo {
        @Schema(description = "接口地址")
        private String url;

        @Schema(description = "接口密钥")
        private String key;

        @Schema(description = "权重")
        private Integer weight;

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
