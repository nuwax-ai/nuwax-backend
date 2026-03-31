package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.ModelConfig;
import com.xspaceagi.agent.core.spec.enums.ModelApiProtocolEnum;
import com.xspaceagi.agent.core.spec.enums.ModelFunctionCallEnum;
import com.xspaceagi.agent.core.spec.enums.ModelTypeEnum;
import com.xspaceagi.agent.core.spec.enums.UsageScenarioEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ModelConfigAddDto implements Serializable {

    @Schema(description = "模型ID（可选，不传递为新增，传递了为更新）")
    private Long id;

    @Schema(description = "空间ID（可选，在空间中添加模型组件时传递该参数）")
    private Long spaceId;

    @Schema(description = "模型名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "模型名称不能为空")
    private String name; // 模型名称

    @Schema(description = "模型描述")
    private String description; // 模型描述

    @Schema(description = "模型标识", requiredMode = Schema.RequiredMode.REQUIRED, example = "gpt-3.5-turbo")
    @NotNull(message = "模型标识不能为空")
    private String model; // 模型标识

    @Schema(description = "模型类型，可选值：Completions, Chat, Edits, Images, Embeddings, Audio, Other", requiredMode = Schema.RequiredMode.REQUIRED)
    private ModelTypeEnum type; // 模型类型，可选值：Completions, Chat, Edits, Images, Embeddings, Audio, Other

    @Schema(description = "最大输出token数")
    private Integer maxTokens; // token上限

    @Schema(description = "上下文最大输出token数")
    private Integer maxContextTokens; // 上下文token上限

    @Schema(description = "模型接口协议，可选值：OpenAI, Ollama", requiredMode = Schema.RequiredMode.REQUIRED)
    private ModelApiProtocolEnum apiProtocol; // 模型接口协议，可选值：OpenAI, Ollama

    @Schema(description = "API列表", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "API列表不能为空")
    private List<ModelConfigDto.ApiInfo> apiInfoList; // API列表 [{"url":"","key":"","weight":1}]

    @Schema(description = "接口调用策略，可选值：RoundRobin, WeightedRoundRobin, LeastConnections, WeightedLeastConnections, Random, ResponseTime", requiredMode = Schema.RequiredMode.REQUIRED)
    private ModelConfig.ModelStrategyEnum strategy; // 接口调用策略，可选值：RoundRobin, WeightedRoundRobin, LeastConnections, WeightedLeastConnections, Random, ResponseTime

    @Schema(description = "向量维度")
    private Integer dimension; // 向量维度

    @Schema(description = "函数调用支持，可选范围：CallSupported 支持普通函数调用; StreamCallSupported 支持流式函数调用；Unsupported 不支持函数调用")
    private ModelFunctionCallEnum functionCall;

    @Schema(description = "是否为推理模型，0 否；1 是")
    private Integer isReasonModel;

    @Schema(description = "是否启用,1-启用，0-禁用")
    private Integer enabled;

    @Schema(description = "访问控制，可选值：0-不管控，1-管控")
    private Integer accessControl;

    @Schema(description = "可使用的业务场景")
    private List<UsageScenarioEnum> usageScenarios;
}
