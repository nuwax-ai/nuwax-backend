package com.xspaceagi.agent.core.adapter.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.agent.core.spec.enums.ModelApiProtocolEnum;
import com.xspaceagi.agent.core.spec.enums.ModelFunctionCallEnum;
import com.xspaceagi.agent.core.spec.enums.ModelTypeEnum;
import lombok.Data;

import java.util.Date;

@TableName("model_config")
@Data
public class ModelConfig {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id; // 模型ID

    @TableField(value = "_tenant_id")
    private Long tenantId; // 商户ID
    private Long creatorId;
    private Long spaceId;

    private ModelScopeEnum scope; // 模型生效范围，可选值：Space, Tenant, Global

    private String name; // 模型名称

    private String description; // 模型描述

    private String model; // 模型标识

    private ModelTypeEnum type; // 模型类型，可选值：Completions, Chat, Edits, Images, Embeddings, Audio, Other
    private Integer isReasonModel;
    private NetworkType networkType;

    private String natInfo;

    private ModelFunctionCallEnum functionCall; // 函数调用支持程度，可选值：Unsupported, CallSupported, StreamCallSupported

    private Integer maxTokens; // 请求token上限
    private Integer maxContextTokens; // 上下文token上限

    private ModelApiProtocolEnum apiProtocol; // 模型接口协议，可选值：OpenAI, Ollama

    private String apiInfo; // API列表 [{"url":"","key":"","weight":1}]

    private ModelStrategyEnum strategy; // 接口调用策略，可选值：RoundRobin, WeightedRoundRobin, LeastConnections, WeightedLeastConnections, Random, ResponseTime

    private Integer dimension; // 向量维度

    private Date modified; // 修改时间

    private Date created; // 创建时间

    private Integer enabled; // 是否启用，0-否，1-是
    private Integer accessControl;
    private String usageScenario;
    public enum ModelScopeEnum {
        Space,
        Tenant,
        Global
    }

    public enum ModelStrategyEnum {
        RoundRobin,
        WeightedRoundRobin,
        LeastConnections,
        WeightedLeastConnections,
        Random,
        ResponseTime
    }

    //ENUM('Internet','Intranet')
    public enum NetworkType {
        Internet,
        Intranet
    }
}
