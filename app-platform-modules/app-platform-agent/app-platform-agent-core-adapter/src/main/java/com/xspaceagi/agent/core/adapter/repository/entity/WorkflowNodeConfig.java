package com.xspaceagi.agent.core.adapter.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.agent.core.spec.handler.JsonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

@Data
@TableName(value = "workflow_node_config", autoResultMap = true)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkflowNodeConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("_tenant_id")
    private Long tenantId;
    private String name;
    private String icon;
    private String description;
    private Long workflowId;
    private NodeType type;
    private String config; // 使用 String 类型来存储 JSON 数据
    @TableField(value = "next_node_ids", typeHandler = JsonTypeHandler.class)
    private List<Long> nextNodeIds; // 使用 String 类型来存储 JSON 数据
    private Long loopNodeId;
    private Long innerStartNodeId;
    private Long innerEndNodeId;
    private Date modified;
    private Date created;

    public enum NodeType {
        Start("开始", "工作流起始节点，用于设定启动工作流需要的信息"), // 开始节点
        LLM("大模型", "调用大语言模型，使用变量和提示词生成回复"),    // 大模型节点
        Plugin("插件", "调用插件，使用变量和插件参数生成回复"), // 插件节点
        Workflow("工作流", "调用工作流，使用变量和工作流参数生成回复"), // 工作流节点
        Output("过程输出", "支持中间过程的消息输出，支持流式和非流式两种方式"), // 过程输出节点
        Code("代码", "编写代码，处理输入变量来生成返回值"),   // 代码节点
        Condition("条件分支", "连接多个下游分支，若设定的条件成立则仅运行对应的分支，若均不成立则只运行“否则”分支"),  // 条件分支节点
        IntentRecognition("意图识别", "用于用户输入的意图识别，并将其与预设意图选项进行匹配"), // 意图识别节点
        Loop("循环", "用于通过设定循环次数和逻辑，重复执行一系列任务"),   // 循环节点
        LoopStart("单次开始", "循环单次起始节点"),
        LoopEnd("单次结束", "循环单次结束节点"),
        LoopContinue("继续循环", "用于终止当前循环，执行下次循环"), // 继续循环节点
        LoopBreak("终止循环", "用于立即终止当前所在的循环，跳出循环体"), // 终止循环节点
        Knowledge("知识库", "在选定的知识中，根据输入变量召回最匹配的信息"), // 知识库节点
        KnowledgeInsert("知识库写入", "向选定的知识库中写入新的内容"), // 知识库写入节点
        TableSQL("SQL自定义", "可支持对数据表放开读写控制，用户可读写其他用户提交的数据，由开发者控制"),
        TableDataAdd("数据新增", "对选定的数据表进行数据写入"),
        TableDataDelete("数据删除", "对选定的数据表根据指定条件进行数据删除"),
        TableDataUpdate("数据更新", "对选定的数据表根据指定条件进行数据更新"),
        TableDataQuery("数据查询", "对选定的数据表根据指定条件进行数据查询"),
        Variable("变量", "用于读取和写入项目中的变量，变量名须与项目中的变量名相匹配"),   // 变量节点
        //VariableAggregation变量聚合
        VariableAggregation("变量聚合", "对多个分支的输出进行聚合处理"),
        LongTermMemory("长期记忆", "用于调用长期记忆，获取用户的个性化信息"),// 长期记忆节点
        QA("问答", "支持中间向用户提问问题"), // 问答节点
        TextProcessing("文本处理", "用于处理多个字符串类型变量的格式"), // 文本处理节点
        DocumentExtraction("文档提取", "用于提取文档内容，支持的文件类型: txt、 markdown、pdf、 html、 xlsx、 xls、 docx、 csv、 md、 htm"), // 文档提取节点
        HTTPRequest("HTTP 请求", "用于配置http请求调用已有的服务"),    // HTTP 请求节点
        Mcp("MCP服务", "用于调用MCP服务"), // MCP服务节点
        Agent("Agent", "用于调用Agent服务"),
        End("结束", "工作流的最终节点，用于返回工作流运行后的结果信息"); // 结束节点

        private String name;
        private String description;

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        NodeType(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }
}
