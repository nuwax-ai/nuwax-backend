package com.xspaceagi.agent.core.infra.component.agent.dto;

import com.alibaba.fastjson2.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class AgentRequest {

    private String user_id;
    private String session_id;
    private String project_id;
    private String agent_work_dir;
    private String request_id;
    private List<Attachment> attachments;
    private List<String> data_source_attachments;
    private ModelProvider model_provider;
    private String prompt;//组装好后的用户提示词
    private String system_prompt;
    private String user_prompt;
    private String original_user_prompt;// 原始用户提示词
    private Boolean open_long_memory;
    private AgentConfig agent_config;


    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @lombok.Data
    public static class Attachment {
        private String type;
        private Content content;
    }


    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @lombok.Data
    public static class Content {
        private String id;
        private Source source;
        private String filename;
        private String description;
    }


    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @lombok.Data
    public static class Source {
        private String source_type;
        private Data data;
    }


    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @lombok.Data
    public static class Data {
        private String data;
        private String mime_type;
    }


    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @lombok.Data
    public static class ModelProvider {
        private String id;
        private String name;
        private String base_url;
        private String api_key;
        private String default_model;
        private boolean requires_openai_auth;
        private String api_protocol;
    }


    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @lombok.Data
    public static class AgentConfig {
        private AgentServer agent_server;
        private Map<String, JSONObject> context_servers;
        private ResourceLimits resource_limits;
    }

    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @lombok.Data
    public static class AgentServer {
        private String agent_id;
        private String command;
        private List<String> args;
        private Map<String, String> env;
        private Metadata metadata;
        private String agent_mode;
        private Map<String, Map<String, String>> platforms;
        private List<Map<String, Object>> tool_approval_rules;
        private String version;
    }

    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @lombok.Data
    public static class Metadata {
        private String version;
        private String description;
        private String note;
        private List<String> capabilities;
    }


    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @lombok.Data
    public static class ResourceLimits {
        private long memory_limit;
        private int cpu_limit;
        private long swap_limit;
    }
}