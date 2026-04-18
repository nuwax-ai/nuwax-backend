package com.xspaceagi.agent.core.adapter.dto.config.workflow;

import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.InputArg;
import com.xspaceagi.agent.core.adapter.dto.config.OutputArg;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.agent.core.spec.enums.SearchStrategyEnum;
import com.xspaceagi.system.spec.utils.I18nUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.util.CollectionUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class KnowledgeNodeConfigDto extends NodeConfigDto {

    @Schema(description = "知识库配置", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Knowledge base configuration is required")
    private List<KnowledgeBaseConfigDto> knowledgeBaseConfigs;

    //搜索策略
    @Schema(description = "搜索策略")
    private SearchStrategyEnum searchStrategy;

    //最大召回数量
    @Schema(description = "最大召回数量")
    private Integer maxRecallCount;

    //匹配度
    @Schema(description = "匹配度,0.01 - 0.99")
    private Double matchingDegree;

    @Data
    public static class KnowledgeBaseConfigDto implements Serializable {

        @Schema(description = "知识库ID", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Knowledge base ID is required")
        private Long knowledgeBaseId;

        private String name;

        private String description;

        private String icon;

        /**
         * 知识库类型 ,固定 : Knowledge
         */
        private String type = "Knowledge";
    }


    /**
     * 公共节点配置转条件节点配置
     *
     * @param nodeConfigDto 公共节点配置
     * @return 条件节点配置
     */
    public static KnowledgeNodeConfigDto addFrom(NodeConfigDto nodeConfigDto, List<KnowledgeNodeConfigDto.KnowledgeBaseConfigDto> knowledgeBaseConfigs) {
        KnowledgeNodeConfigDto conditionNodeConfigDto = new KnowledgeNodeConfigDto();
        conditionNodeConfigDto.setExtension(nodeConfigDto.getExtension());
        conditionNodeConfigDto.setInputArgs(nodeConfigDto.getInputArgs());
        conditionNodeConfigDto.setOutputArgs(nodeConfigDto.getOutputArgs());


        //设置默认输入参数和输出参数
        List<Arg> args = new ArrayList<>();
        InputArg arg = new InputArg();
        arg.setName("Query");
        arg.setDataType(DataTypeEnum.String);
        arg.setRequire(true);
        args.add(arg);
        conditionNodeConfigDto.setInputArgs(args);

        List<Arg> outputArgs = new ArrayList<>();
        Arg outArg = new OutputArg();
        outArg.setName("outputList");
        outArg.setDataType(DataTypeEnum.Array_Object);
        outputArgs.add(outArg);
        List<Arg> subArgs = new ArrayList<>();
        Arg subArg = new OutputArg();
        subArg.setName("output");
        subArg.setDescription(I18nUtil.systemMessage("Backend.WorkflowKbArgs.output.description"));
        subArg.setDataType(DataTypeEnum.String);
        subArgs.add(subArg);
        subArg = new OutputArg();
        subArg.setName("rawText");
        subArg.setDescription(I18nUtil.systemMessage("Backend.WorkflowKbArgs.rawText.description"));
        subArg.setDataType(DataTypeEnum.String);
        subArgs.add(subArg);
        outArg.setSubArgs(subArgs);
        conditionNodeConfigDto.setOutputArgs(outputArgs);


        //默认知识库配置
        conditionNodeConfigDto.matchingDegree = 0.5;
        conditionNodeConfigDto.maxRecallCount = 5;
        conditionNodeConfigDto.setSearchStrategy(SearchStrategyEnum.MIXED);

        //前端传的知识库配置,如果有给的话
        if (!CollectionUtils.isEmpty(knowledgeBaseConfigs)) {
            conditionNodeConfigDto.setKnowledgeBaseConfigs(knowledgeBaseConfigs);
        }

        return conditionNodeConfigDto;

    }


}
