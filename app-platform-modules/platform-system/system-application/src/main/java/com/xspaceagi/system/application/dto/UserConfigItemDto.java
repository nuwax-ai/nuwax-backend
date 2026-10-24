package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class UserConfigItemDto implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "配置项 key")
    private String key;

    @Schema(description = "配置项值（任意 JSON：标量/对象/数组）")
    private Object value;

    @Schema(description = "备注")
    private String description;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "更新时间")
    private Date modified;
}
