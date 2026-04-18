package com.xspaceagi.agent.core.adapter.dto.config.workflow;

import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.bind.ModelBindConfigDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class QaNodeConfigDto extends NodeConfigDto {

    @Schema(description = "LLM模型ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "LLM model ID is required")
    private Long modelId;

    @Schema(description = "模式：Precision 精确模式；Balanced 平衡模式；Creative 创意模式；Customization 自定义")
    private ModelBindConfigDto.Mode mode;

    @Schema(description = "生成随机性;0-1")
    private Double temperature;

    @Schema(description = "累计概率: 模型在生成输出时会从概率最高的词汇开始选择;0-1")
    private Double topP;

    @Schema(description = "token上限")
    private Integer maxTokens; // token上限

    @Schema(description = "提问问题")
    private String question;

    //回答类型
    @Schema(description = "回答类型，如果为用户直接回答（TEXT），下级节点参数使用节点上的nextNodeIds；如果为选项回答（SELECT），下级节点参数使用option上的nextNodeIds", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Answer type is required")
    private AnswerTypeEnum answerType;

    //从回复中提取字段
    @Schema(description = "从回复中提取字段")
    private Boolean extractField;

    //最多回复次数
    @Schema(description = "最多回复次数")
    private Integer maxReplyCount;

    //选项列表
    @Schema(description = "选项列表")
    private List<OptionConfigDto> options;

    @Schema(hidden = true)
    private ModelConfigDto modelConfig;

    public enum AnswerTypeEnum {
        //直接回答、选项回答
        TEXT,
        SELECT
    }

    @Data
    public static class OptionConfigDto {

        private String uuid;

        //选项编号，例如A、B、C
        @Schema(description = "选项编号，例如A、B、C")
        private String index;

        @Schema(description = "选项内容")
        private String content;

        @Schema(description = "关联下级节点id列表")
        private List<Long> nextNodeIds;
    }

    public Integer getMaxReplyCount() {
        return maxReplyCount == null || maxReplyCount == 0 ? 3 : maxReplyCount;
    }
}
