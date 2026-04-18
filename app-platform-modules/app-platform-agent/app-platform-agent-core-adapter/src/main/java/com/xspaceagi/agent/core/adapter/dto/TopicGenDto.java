package com.xspaceagi.agent.core.adapter.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class TopicGenDto implements Serializable {

    @JsonPropertyDescription("Contextually appropriate titles, ≤50 characters. Do not generate titles unrelated to the topic. For meaningless contexts, such as user input errors or arbitrary content, no summary is needed; return empty. Output language based on the <content>")
    private String topic;

}
