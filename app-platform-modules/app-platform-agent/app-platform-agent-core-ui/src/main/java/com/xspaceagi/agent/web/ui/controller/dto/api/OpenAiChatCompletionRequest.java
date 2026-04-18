package com.xspaceagi.agent.web.ui.controller.dto.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAiChatCompletionRequest implements Serializable {

    @JsonProperty("model")
    private String model;

    @JsonProperty("messages")
    private List<Message> messages;

    @JsonProperty("temperature")
    private Double temperature;

    @JsonProperty("top_p")
    private Double topP;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    @JsonProperty("stream")
    private Boolean stream;

    @JsonProperty("stop")
    private List<String> stop;

    @JsonProperty("stream_options")
    private StreamOptions streamOptions;

    @JsonProperty("user")
    private String user;

    @JsonProperty("extra_params")
    private Map<String, Object> extraParams;

    @Data
    public static class Message implements Serializable {
        @JsonProperty("role")
        private String role;

        @JsonProperty("content")
        private String content;
    }

    @Data
    public static class StreamOptions implements Serializable {
        @JsonProperty("include_usage")
        private Boolean includeUsage;
    }
}
