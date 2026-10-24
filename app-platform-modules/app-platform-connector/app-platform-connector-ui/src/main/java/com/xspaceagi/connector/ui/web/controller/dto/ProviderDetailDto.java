package com.xspaceagi.connector.ui.web.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "provider 详情 + action 列表")
public class ProviderDetailDto {

    private ProviderDto provider;

    private List<ActionDto> actions;
}
