package com.xspaceagi.agent.core.infra.component.plugin;

import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.system.sdk.common.TraceContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PluginContext implements Serializable {

    private PluginDto pluginDto;

    private PluginConfigDto pluginConfig;

    private Map<String, Object> params;

    private Long userId;

    private String requestId;

    private AgentContext agentContext;

    private Long startTime;

    private Long endTime;

    private boolean executeSuccess;

    private List<String> logs;

    private String error;

    private boolean test;

    private boolean asyncExecute;

    private String asyncReplyContent;

    private TraceContext traceContext;
}
