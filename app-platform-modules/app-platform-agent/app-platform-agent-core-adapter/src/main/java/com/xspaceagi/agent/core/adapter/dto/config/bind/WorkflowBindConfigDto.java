package com.xspaceagi.agent.core.adapter.dto.config.bind;

import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class WorkflowBindConfigDto implements Serializable {

    @Schema(description = "参数绑定信息（入参）")
    private List<Arg> argBindConfigs;

    @Schema(description = "参数绑定信息（出参）")
    private List<Arg> outputArgBindConfigs;

    @Schema(description = "卡片绑定信息")
    private CardBindConfigDto cardBindConfig;

    @Schema(description = "调用方式")
    private WorkflowInvokeTypeEnum invokeType;

    @Schema(description = "是否异步执行，0-否，1-是")
    private Integer async;

    @Schema(description = "异步执行时回复内容")
    private String asyncReplyContent;

    @Schema(description = "是否默认选中，0-否，1-是")
    private Integer defaultSelected;

    @Schema(description = "是否直接输出，0-否，1-是")
    private Integer directOutput;

    // 相同requestId时是否使用缓存的结果，问答场景
    private boolean useResultCache;

    public enum WorkflowInvokeTypeEnum {
        //自动调用、按需调用
        AUTO, ON_DEMAND, MANUAL, MANUAL_ON_DEMAND
    }

    public void setInputArgBindConfigs(List<Arg> inputArgBindConfigs) {
        if (this.argBindConfigs != null) {
            return;
        }
        this.argBindConfigs = inputArgBindConfigs;
    }

    public List<Arg> getInputArgBindConfigs() {
        return argBindConfigs;
    }
}
