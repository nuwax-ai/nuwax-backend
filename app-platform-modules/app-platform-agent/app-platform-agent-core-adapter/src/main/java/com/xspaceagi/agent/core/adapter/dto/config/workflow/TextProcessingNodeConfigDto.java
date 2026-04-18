package com.xspaceagi.agent.core.adapter.dto.config.workflow;

import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.OutputArg;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.system.spec.utils.I18nUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TextProcessingNodeConfigDto extends NodeConfigDto {

    //处理类型
    @Schema(description = "处理类型", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Processing type is required")
    private TextHandleTypeEnum textHandleType;

    //字符串拼接内容
    @Schema(description = "字符串拼接内容")
    private String text;

    @Schema(description = "字符串拼接符")
    private String join;

    //字符串分割符
    @Schema(description = "字符串分割符")
    private List<String> splits;

    public enum TextHandleTypeEnum {
        //拼接
        CONCAT,
        //分割
        SPLIT
    }


    /**
     * 公共节点配置转条件节点配置
     *
     * @param nodeConfigDto 公共节点配置
     * @return 条件节点配置
     */
    public static TextProcessingNodeConfigDto addFrom(NodeConfigDto nodeConfigDto) {
        TextProcessingNodeConfigDto intentRecognitionNodeConfigDto = new TextProcessingNodeConfigDto();
        intentRecognitionNodeConfigDto.setExtension(nodeConfigDto.getExtension());
        intentRecognitionNodeConfigDto.setInputArgs(nodeConfigDto.getInputArgs());

        intentRecognitionNodeConfigDto.setTextHandleType(TextHandleTypeEnum.CONCAT);

        //设置系统默认的出参,固定的,不能修改


        List<Arg> outputArgs = obtainDefaultOutputArgs(TextHandleTypeEnum.CONCAT);
        intentRecognitionNodeConfigDto.setOutputArgs(outputArgs);


        return intentRecognitionNodeConfigDto;

    }

    /**
     * 获取系统默认的意图识别出参
     *
     * @return
     */
    public static List<Arg> obtainDefaultOutputArgs(TextProcessingNodeConfigDto.TextHandleTypeEnum textHandleType) {
        List<Arg> outputArgs = new ArrayList<>();

        if (textHandleType == TextProcessingNodeConfigDto.TextHandleTypeEnum.CONCAT) {
            Arg outArg = new OutputArg();
            outArg.setName("output");
            outArg.setDescription(I18nUtil.systemMessage("Backend.WorkflowTextArgs.output.description"));
            outArg.setDataType(DataTypeEnum.String);
            outArg.setSystemVariable(true);
            outArg.setRequire(true);
            outputArgs.add(outArg);
        } else {
            Arg outArg = new OutputArg();

            outArg.setName("output");
            outArg.setDescription(I18nUtil.systemMessage("Backend.WorkflowTextArgs.output.description"));
            outArg.setDataType(DataTypeEnum.Array_String);
            outArg.setSystemVariable(true);
            outArg.setRequire(true);

            outputArgs.add(outArg);
        }
        return outputArgs;
    }

}
