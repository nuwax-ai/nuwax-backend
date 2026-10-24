package com.xspaceagi.userapp.domain.model;

import java.util.Date;

import com.xspaceagi.userapp.sdk.dto.UserAppDomainType;

import lombok.Data;

@Data
public class UserAppDomainModel {

    private Long id;

    private Long tenantId;

    private Long appId;

    private String domain;

    private UserAppDomainType domainType;

    private Date created;

    private Date modified;
}
