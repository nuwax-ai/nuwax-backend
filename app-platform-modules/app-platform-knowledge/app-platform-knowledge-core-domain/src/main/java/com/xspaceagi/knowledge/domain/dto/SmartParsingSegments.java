package com.xspaceagi.knowledge.domain.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class SmartParsingSegments {

    @JsonPropertyDescription("The starting position (character index from 0) of this segment in the original text. Must be as accurate as possible, pointing to the first character of the chapter title.")
    private Integer position;
    @JsonPropertyDescription("The text feature at the beginning of this segment (first 30-50 characters) for position verification. Used to validate the accuracy of the position field.")
    private String startText;
}
