package com.xspaceagi.eco.market.domain.model;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EcoMarketImportRecordModel {

    private Long id;

    private Long tenantId;

    private Long userId;

    private Long spaceId;

    private String targetType;

    private Long targetId;

    private String ecoTargetId;

    private LocalDateTime created;

    private LocalDateTime modified;
}
