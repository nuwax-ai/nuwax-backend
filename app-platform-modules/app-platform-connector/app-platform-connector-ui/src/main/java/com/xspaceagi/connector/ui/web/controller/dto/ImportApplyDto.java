package com.xspaceagi.connector.ui.web.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "导入 apply 请求")
public class ImportApplyDto {

    @NotBlank(message = "必填")
    private String importId;
}
