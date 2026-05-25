package com.xspaceagi.bill.app.service;

import com.xspaceagi.bill.sdk.dto.*;
import com.xspaceagi.bill.spec.enums.RevenueStatusEnum;

import java.util.List;

public interface BillRevenueAppService {

    boolean addRevenue(AddRevenueRequest request);

    List<DailyRevenueDTO> queryDailyRevenue(RevenueQueryRequest query);

    RevenueDetailPageDTO queryRevenueDetail(RevenueQueryRequest query);

    RevenueStatsDTO getRevenueStats(Long userId);

    RevenueStatsDTO getAdminRevenueStats(String monthStart, String monthEnd, Long userId, RevenueStatusEnum status, Integer pageNum, Integer pageSize);

    RevenueDetailPageDTO getUserRevenueDetails(Long userId, String dt, Integer pageNum, Integer pageSize);
}
