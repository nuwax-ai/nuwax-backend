package com.xspaceagi.system.application.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TenantAddDto {

    @NotNull(message = "Tenant name is required")
    private String name;

    private String description;

    private String logo;

    @NotNull(message = "Tenant domain is required")
    private String domain;

    @NotNull(message = "Tenant admin username is required")
    private String userName;

    private String nickName;

    @NotNull(message = "Tenant admin phone is required")
    private String phone;

    @NotNull(message = "Tenant admin email is required")
    private String email;

    @NotNull(message = "Tenant admin password is required")
    private String password;
}
