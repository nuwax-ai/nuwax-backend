package com.xspaceagi.custompage.ui.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Domain binding delete request body")
public class CustomPageDomainDeleteReq {

    @NotNull(message = "id is required")
    @Schema(description = "Domain binding ID", required = true)
    private Long id;

}
