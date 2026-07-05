package com.xspaceagi.agent.core.adapter.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PackageAgentDto implements Serializable {

    @NotNull
    private Long userId;

    @NotNull
    @JsonProperty("cId")
    private Long cId;

    @NotNull
    private Long agentId;

    @NotNull
    private InitProjectTemplateDto.ProgrammingLanguage programmingLanguage;
}
