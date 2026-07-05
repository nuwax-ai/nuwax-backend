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

    @JsonPropertyDescription("SVG icon, viewBox='0 0 100 100'. Do not generate icons unrelated to the topic. For meaningless contexts, such as user input errors or arbitrary content, no icon is needed; return empty. Output language based on the <content>. \nSVG requirements: \n- Use simple geometric shapes and clean lines \n- Use a modern color palette (gradient or solid colors) \n- Must be a valid, self-contained SVG with no external references \n- Keep it simple and recognizable at small sizes \n- The SVG should be visually appealing and professional")
    private String svgIcon;
}
