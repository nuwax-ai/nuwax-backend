package com.xspaceagi.custompage.ui.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Domain binding create request body")
public class CustomPageDomainCreateReq {

    @NotNull(message = "projectId is required")
    @Schema(description = "Project ID", required = true)
    private Long projectId;

    @NotBlank(message = "domain is required")
    @Schema(description = "Domain name", required = true)
    private String domain;

}
