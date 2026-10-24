package com.xspaceagi.connector.ui.web.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
@Schema(description = "连接器 action 视图")
public class ActionDto {

    private Long id;

    private String providerService;

    private String actionKey;

    private String name;

    private String description;

    private List<String> tags;

    private Object inputArgs;

    private Object outputArgs;

    private String execType;

    private String execRef;

    private Object httpSpec;

    private String managedBy;

    private Boolean deprecated;

    private String status;

    private Date modified;

    private Date created;
}
