package com.xspaceagi.agent.core.adapter.dto.config.workflow;

import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class LoopNodeConfigDto extends NodeConfigDto {

    //循环类型
    @Schema(description = "循环类型，数组循环时，循环数组使用inputArgs", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Loop type is required")
    private LoopTypeEnum loopType;

    @Schema(description = "中间变量")
    private List<Arg> variableArgs;

    private Integer loopTimes;

    //循环类型枚举包括 使用数组循环、指定次数循环、无限循环
    public enum LoopTypeEnum {
        //使用数组循环
        ARRAY_LOOP,
        //指定次数循环
        SPECIFY_TIMES_LOOP,
        //无限循环
        INFINITE_LOOP
    }

}
