package com.xspaceagi.knowledge.domain.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class SmartParsingTopicGenDto implements Serializable {

    @JsonPropertyDescription("List of document segments with their positions and text features. Each segment contains: 1) position - starting position in original text (character index from 0), 2) startText - text feature at segment beginning (first 30-50 characters) for position verification. Ensure each segment is semantically complete without content omission. Example: [{\"position\": 0, \"startText\": \"一、引言\"}, {\"position\": 12, \"startText\": \"二、方法\"}]")
    private List<SmartParsingSegments> segments;

}
