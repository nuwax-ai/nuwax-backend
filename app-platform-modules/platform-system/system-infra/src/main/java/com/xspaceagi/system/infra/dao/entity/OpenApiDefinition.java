package com.xspaceagi.system.infra.dao.entity;

import lombok.Data;

import java.util.List;

@Data
public class OpenApiDefinition {
    private String name;
    private String key;
    private User.Role role;
    private String path;
    private List<OpenApiDefinition> apiList;
}
