package com.xspaceagi.agent.core.adapter.dto.config;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class TriggerConfigDto implements Serializable {

    //触发器名称
    @Schema(description = "触发器名称")
    @NotNull(message = "Trigger name is required")
    private String name;

    //触发类型
    @Schema(description = "触发类型,TIME 定时触发, EVENT 事件触发")
    @NotNull(message = "Trigger type is required")
    private TriggerTypeEnum triggerType;

    //触发时区
    @Schema(description = "定时触发-时区，例如 Asia/Shanghai")
    private String timeZone;

    @Schema(description = "定时触发-utc时区，例如 UTC+08:00")
    private String utc;

    //触发时间，cron
    @Schema(description = "定时触发-触发时间，cron表达式（将中文描述转换）")
    private String cronExpression;

    //触发时间，cron描述
    @Schema(description = "定时触发-触发时间，cron描述")
    private String cronDesc;

    //触发事件
    @Schema(description = "事件触发-请求的Bearer Token")
    private String eventBearerToken;

    //触发事件
    @Schema(description = "事件触发-请求的参数定义")
    private List<Arg> eventArgs;

    //触发器执行的组件类型
    @Schema(description = "触发器执行的组件类型")
    private TriggerComponentTypeEnum componentType;

    //触发器执行的组件名称
    @Schema(description = "触发器执行的组件名称")
    private String componentName;

    //触发器执行的组件ID
    @Schema(description = "触发器执行的组件ID")
    private Long componentId;

    @Schema(description = "组件参数绑定信息")
    private List<Arg> argBindConfigs;

    public enum TriggerComponentTypeEnum {
        // 插件、工作流
        PLUGIN, WORKFLOW
    }

    public enum TriggerTypeEnum {
        //定时触发、事件触发
        TIME, EVENT
    }

}
