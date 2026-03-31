package com.xspaceagi.agent.core.adapter.dto.config.bind;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ModelBindConfigDto implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "模式：Precision 精确模式；Balanced 平衡模式；Creative 创意模式；Customization 自定义")
    private Mode mode;

    @Schema(description = "生成随机性;0-1")
    private Double temperature;

    @Schema(description = "累计概率: 模型在生成输出时会从概率最高的词汇开始选择;0-1")
    private Double topP;

    @Schema(description = "最大生成长度")
    private Integer maxTokens;

    @Schema(description = "上下文轮数")
    private Integer contextRounds;

    @Schema(description = "推理模型ID")
    private Long reasoningModelId;

    // 已废弃
    @Schema(description = "代理引擎")
    private AgentEngine agentEngine;

    public enum AgentEngine {
        Default,
        NuwaxCli
    }

    public enum Mode {
        Precision,
        Balanced,
        Creative,
        Customization
    }
}
