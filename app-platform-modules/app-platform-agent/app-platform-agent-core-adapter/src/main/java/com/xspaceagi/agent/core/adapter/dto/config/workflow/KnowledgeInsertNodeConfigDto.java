package com.xspaceagi.agent.core.adapter.dto.config.workflow;

import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.InputArg;
import com.xspaceagi.agent.core.adapter.dto.config.OutputArg;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.system.spec.utils.I18nUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
public class KnowledgeInsertNodeConfigDto extends NodeConfigDto {

    @Schema(description = "知识库ID配置", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long knowledgeBaseId;

    @Schema(description = "节点名称")
    private String name;

    @Schema(description = "节点描述")
    private String description;

    @Schema(description = "节点图标")
    private String icon;

    /**
     * 公共节点配置转条件节点配置
     *
     * @return 条件节点配置
     */
    public static KnowledgeInsertNodeConfigDto addFrom(Long knowledgeBaseId) {
        KnowledgeInsertNodeConfigDto knowledgeInsertNodeConfigDto = new KnowledgeInsertNodeConfigDto();
        knowledgeInsertNodeConfigDto.setKnowledgeBaseId(knowledgeBaseId);
        //设置默认输入参数和输出参数
        List<Arg> args = new ArrayList<>();
        InputArg arg = new InputArg();
        arg.setKey(UUID.randomUUID().toString().replace("-", ""));
        arg.setName("title");
        arg.setDescription(I18nUtil.systemMessage("Backend.WorkflowKbArgs.title.description"));
        arg.setDataType(DataTypeEnum.String);
        args.add(arg);
        arg = new InputArg();
        arg.setKey(UUID.randomUUID().toString().replace("-", ""));
        arg.setName("content");
        arg.setDescription(I18nUtil.systemMessage("Backend.WorkflowKbArgs.content.description"));
        arg.setDataType(DataTypeEnum.String);
        args.add(arg);
        arg = new InputArg();
        arg.setKey(UUID.randomUUID().toString().replace("-", ""));
        arg.setName("docUrl");
        arg.setDescription(I18nUtil.systemMessage("Backend.WorkflowKbArgs.docUrl.description"));
        arg.setDataType(DataTypeEnum.String);
        args.add(arg);
        //segment
        arg = new InputArg();
        arg.setKey(UUID.randomUUID().toString().replace("-", ""));
        arg.setName("segment");
        arg.setDescription(I18nUtil.systemMessage("Backend.WorkflowKbArgs.segment.description"));
        arg.setDataType(DataTypeEnum.String);
        arg.setBindValue("WORDS");
        args.add(arg);
        arg = new InputArg();
        arg.setKey(UUID.randomUUID().toString().replace("-", ""));
        arg.setName("words");
        arg.setDescription(I18nUtil.systemMessage("Backend.WorkflowKbArgs.words.description"));
        arg.setDataType(DataTypeEnum.Integer);
        arg.setBindValue("800");
        args.add(arg);

        knowledgeInsertNodeConfigDto.setInputArgs(args);

        List<Arg> outputArgs = new ArrayList<>();
        Arg outArg = new OutputArg();
        outArg.setName("success");
        outArg.setDataType(DataTypeEnum.Boolean);
        outArg.setDescription(I18nUtil.systemMessage("Backend.WorkflowKbArgs.success.description"));
        outputArgs.add(outArg);
        outArg = new OutputArg();
        outArg.setName("message");
        outArg.setDataType(DataTypeEnum.String);
        outArg.setDescription(I18nUtil.systemMessage("Backend.WorkflowKbArgs.message.description"));
        outputArgs.add(outArg);
        outArg = new OutputArg();
        outArg.setName("docId");
        outArg.setDataType(DataTypeEnum.Number);
        outArg.setDescription(I18nUtil.systemMessage("Backend.WorkflowKbArgs.docId.description"));
        outputArgs.add(outArg);
        knowledgeInsertNodeConfigDto.setOutputArgs(outputArgs);
        return knowledgeInsertNodeConfigDto;
    }
}
