package com.xspaceagi.bill.app.service;

import com.xspaceagi.bill.sdk.dto.ResourceStatPageDTO;
import com.xspaceagi.bill.sdk.dto.ResourceStatSummaryDTO;

public interface ResourceStatAppService {

    ResourceStatPageDTO queryResourceStats(Long tenantId, Long userId, String type,
                                           String targetType, Long targetId,
                                           String dtStart, String dtEnd,
                                           Integer pageNum, Integer pageSize);

    ResourceStatSummaryDTO getResourceStatSummary(Long tenantId, Long userId,
                                                  String dtStart, String dtEnd);
}
