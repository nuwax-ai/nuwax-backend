package com.xspaceagi.agent.core.adapter.dto.config.workflow;

import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.bind.ModelBindConfigDto;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.system.sdk.retry.utils.GeneratorUtils;
import com.xspaceagi.system.spec.utils.I18nUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 意图识别节点配置
 */
@Data
public class IntentRecognitionNodeConfigDto extends NodeConfigDto {

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

    @Schema(description = "系统提示词")
    private String systemPrompt;

    @Schema(description = "补充提示词")
    private String extraPrompt;

    //使用对话历史
    @Schema(description = "是否使用对话历史")
    private Boolean useHistory;

    //意图配置列表
    @Schema(description = "意图配置列表")
    private List<IntentConfigDto> intentConfigs;

    @Schema(hidden = true)
    private ModelConfigDto modelConfig;

    @Data
    public static class IntentConfigDto {

        @Schema(description = "唯一标识")
        private String uuid;

        @Schema(description = "意图名称")
        private String name;

        @Schema(description = "意图描述，兜底匹配")
        private String intent;

        @Schema(description = "意图类型,NORMAL正常添加配置,OTHER其他意图")
        private IntentTypeEnum intentType;

        //条件参数关系
        @Schema(description = "条件参数之间关系")
        private ConditionNodeConfigDto.ConditionTypeEnum conditionType;

        //参数列表配置
        @Schema(description = "参数列表配置")
        private List<ConditionNodeConfigDto.ConditionArgDto> conditionArgs;

        @Schema(description = "关联下级节点id列表")
        private List<Long> nextNodeIds;
    }

    public enum IntentTypeEnum {
        //如果
        NORMAL,
        OTHER,
    }

    /**
     * 公共节点配置转条件节点配置
     *
     * @param nodeConfigDto 公共节点配置
     * @return 条件节点配置
     */
    public static IntentRecognitionNodeConfigDto addFrom(NodeConfigDto nodeConfigDto) {
        IntentRecognitionNodeConfigDto intentRecognitionNodeConfigDto = new IntentRecognitionNodeConfigDto();
        intentRecognitionNodeConfigDto.setExtension(nodeConfigDto.getExtension());
        intentRecognitionNodeConfigDto.setInputArgs(nodeConfigDto.getInputArgs());

        //设置系统默认的出参,固定的,不能修改
        List<Arg> outputArgs = obtainDefaultOutputArgs();
        intentRecognitionNodeConfigDto.setOutputArgs(outputArgs);

        //设置固定的意图识别选项:其他意图
        List<IntentConfigDto> intentConfigs = new ArrayList<>();
        IntentConfigDto intentConfigDto = new IntentConfigDto();
        intentConfigDto.setUuid(GeneratorUtils.generateUUID());
        intentConfigDto.setIntent(I18nUtil.systemMessage("Backend.Workflow.Intent.Other"));
        intentConfigDto.setIntentType(IntentTypeEnum.OTHER);
        intentConfigDto.setNextNodeIds(new ArrayList<>());
        intentConfigs.add(intentConfigDto);

        intentRecognitionNodeConfigDto.setIntentConfigs(intentConfigs);

        return intentRecognitionNodeConfigDto;

    }

    /**
     * Get default system output arguments for intent recognition
     *
     * @return
     */
    public static List<Arg> obtainDefaultOutputArgs() {
        List<Arg> outputArgs = new ArrayList<>();
        Arg outArg = new Arg();
        outArg.setName("classificationId");
        outArg.setDescription("Intent classification ID");
        outArg.setDataType(DataTypeEnum.Integer);
        outArg.setSystemVariable(true);
        outArg.setRequire(true);
        outputArgs.add(outArg);

        outArg = new Arg();
        outArg.setName("reason");
        outArg.setDescription("Reason for selecting this intent");
        outArg.setDataType(DataTypeEnum.String);
        outArg.setSystemVariable(true);
        outArg.setRequire(true);
        outputArgs.add(outArg);

        return outputArgs;
    }

}
