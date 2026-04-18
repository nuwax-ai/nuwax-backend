package com.xspaceagi.custompage.ui.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Domain binding update request body")
public class CustomPageDomainUpdateReq {

    @NotNull(message = "id is required")
    @Schema(description = "Domain binding ID", required = true)
    private Long id;

    @NotBlank(message = "domain is required")
    @Schema(description = "Domain name", required = true)
    private String domain;

}
