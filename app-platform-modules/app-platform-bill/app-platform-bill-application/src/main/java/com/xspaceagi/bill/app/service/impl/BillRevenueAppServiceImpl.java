package com.xspaceagi.bill.app.service.impl;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.bill.app.service.BillRevenueAppService;
import com.xspaceagi.bill.infra.dao.entity.BillDailyRevenue;
import com.xspaceagi.bill.infra.dao.entity.BillRevenueDetail;
import com.xspaceagi.bill.infra.dao.mapper.BillDailyRevenueMapper;
import com.xspaceagi.bill.infra.dao.service.IBillDailyRevenueService;
import com.xspaceagi.bill.infra.dao.service.IBillRevenueDetailService;
import com.xspaceagi.bill.sdk.dto.*;
import com.xspaceagi.bill.spec.enums.RevenueStatusEnum;
import com.xspaceagi.bill.spec.enums.RevenueTargetTypeEnum;
import com.xspaceagi.bill.spec.enums.RevenueTypeEnum;
import com.xspaceagi.system.sdk.server.IUserRpcService;
import com.xspaceagi.system.spec.common.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BillRevenueAppServiceImpl implements BillRevenueAppService {

    @Resource
    private IBillDailyRevenueService billDailyRevenueService;

    @Resource
    private IBillRevenueDetailService billRevenueDetailService;

    @Resource
    private BillDailyRevenueMapper billDailyRevenueMapper;

    @Resource
    private IUserRpcService iUserRpcService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean addRevenue(AddRevenueRequest request) {
        log.info("addRevenue: {}", request);
        BillRevenueDetail existing = billRevenueDetailService.lambdaQuery()
                .eq(BillRevenueDetail::getBizNo, request.getBizNo())
                .one();
        if (existing != null) {
            return false;
        }
        return addRevenue0(request);
    }

    private boolean addRevenue0(AddRevenueRequest request) {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        BillRevenueDetail detail = new BillRevenueDetail();
        detail.setTenantId(request.getTenantId());
        detail.setUserId(request.getUserId());
        detail.setDt(today);
        detail.setAmount(request.getAmount());
        detail.setType(request.getType() != null ? request.getType().getCode() : null);
        detail.setTypeId(request.getTypeId());
        detail.setOrderId(request.getOrderId());
        detail.setTargetType(request.getTargetType() != null ? request.getTargetType().getCode() : null);
        detail.setTargetId(request.getTargetId());
        detail.setBizNo(request.getBizNo());
        detail.setRemark(request.getRemark());
        detail.setExtra(request.getExtra() != null ? JSON.toJSONString(request.getExtra()) : null);
        detail.setCreated(new Date());
        billRevenueDetailService.save(detail);

        BillDailyRevenue dailyRevenue = billDailyRevenueService.lambdaQuery()
                .eq(BillDailyRevenue::getUserId, request.getUserId())
                .eq(BillDailyRevenue::getDt, today)
                .one();
        if (dailyRevenue == null) {
            dailyRevenue = BillDailyRevenue.builder()
                    .tenantId(request.getTenantId())
                    .userId(request.getUserId())
                    .dt(today)
                    .amount(request.getAmount())
                    .status(RevenueStatusEnum.PENDING.getCode())
                    .created(new Date())
                    .build();
            billDailyRevenueService.save(dailyRevenue);
        } else {
            dailyRevenue.setAmount(dailyRevenue.getAmount().add(request.getAmount()));
            dailyRevenue.setModified(new Date());
            billDailyRevenueService.updateById(dailyRevenue);
        }
        return true;
    }

    @Override
    public List<DailyRevenueDTO> queryDailyRevenue(RevenueQueryRequest query) {
        List<BillDailyRevenue> list = billDailyRevenueService.lambdaQuery()
                .eq(query.getUserId() != null, BillDailyRevenue::getUserId, query.getUserId())
                .eq(query.getDt() != null, BillDailyRevenue::getDt, query.getDt())
                .orderByDesc(BillDailyRevenue::getDt)
                .list();
        return list.stream().map(this::convertDailyToDTO).collect(Collectors.toList());
    }

    @Override
    public RevenueDetailPageDTO queryRevenueDetail(RevenueQueryRequest query) {
        int pageNum = query.getPageNum() != null ? query.getPageNum() : 1;
        int pageSize = query.getPageSize() != null ? query.getPageSize() : 20;
        List<BillRevenueDetail> list = billRevenueDetailService.lambdaQuery()
                .eq(query.getUserId() != null, BillRevenueDetail::getUserId, query.getUserId())
                .eq(query.getDt() != null, BillRevenueDetail::getDt, query.getDt())
                .eq(query.getType() != null, BillRevenueDetail::getType, query.getType() != null ? query.getType().getCode() : null)
                .eq(query.getTargetType() != null, BillRevenueDetail::getTargetType, query.getTargetType() != null ? query.getTargetType().getCode() : null)
                .eq(query.getTargetId() != null, BillRevenueDetail::getTargetId, query.getTargetId())
                .orderByDesc(BillRevenueDetail::getCreated)
                .last("LIMIT " + ((pageNum - 1) * pageSize) + ", " + pageSize)
                .list();
        Long total = billRevenueDetailService.lambdaQuery()
                .eq(query.getUserId() != null, BillRevenueDetail::getUserId, query.getUserId())
                .eq(query.getDt() != null, BillRevenueDetail::getDt, query.getDt())
                .eq(query.getType() != null, BillRevenueDetail::getType, query.getType() != null ? query.getType().getCode() : null)
                .eq(query.getTargetType() != null, BillRevenueDetail::getTargetType, query.getTargetType() != null ? query.getTargetType().getCode() : null)
                .eq(query.getTargetId() != null, BillRevenueDetail::getTargetId, query.getTargetId())
                .count();

        RevenueDetailPageDTO page = new RevenueDetailPageDTO();
        page.setRecords(list.stream().map(this::convertDetailToDTO).collect(Collectors.toList()));
        page.setTotal(total);
        page.setPageNum(pageNum);
        page.setPageSize(pageSize);
        return page;
    }

    @Override
    public RevenueStatsDTO getRevenueStats(Long userId) {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String monthStart = LocalDate.now().withDayOfMonth(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        Map<String, Object> stats = billDailyRevenueMapper.selectStatsByUserId(userId, today, monthStart);

        RevenueStatsDTO dto = new RevenueStatsDTO();
        dto.setTotalRevenue(toBigDecimal(stats.get("totalRevenue")));
        dto.setTodayRevenue(toBigDecimal(stats.get("todayRevenue")));
        dto.setMonthRevenue(toBigDecimal(stats.get("monthRevenue")));
        dto.setPendingAmount(toBigDecimal(stats.get("pendingAmount")));
        dto.setSettledAmount(toBigDecimal(stats.get("settledAmount")));
        dto.setUnsettledAmount(toBigDecimal(stats.get("unsettledAmount")));

        List<BillDailyRevenue> dailyList = billDailyRevenueService.lambdaQuery()
                .eq(BillDailyRevenue::getUserId, userId)
                .orderByDesc(BillDailyRevenue::getDt)
                .list();
        dto.setDailyRevenues(dailyList.stream().map(this::convertDailyToDTO).collect(Collectors.toList()));
        return dto;
    }

    @Override
    public RevenueStatsDTO getAdminRevenueStats(String monthStart, String monthEnd, Long userId, RevenueStatusEnum status, Integer pageNum, Integer pageSize) {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        Map<String, Object> stats = billDailyRevenueMapper.selectAdminStats(monthStart, monthEnd, today, userId);

        RevenueStatsDTO dto = new RevenueStatsDTO();
        dto.setTotalRevenue(toBigDecimal(stats.get("totalRevenue")));
        dto.setTodayRevenue(toBigDecimal(stats.get("todayRevenue")));
        dto.setMonthRevenue(toBigDecimal(stats.get("monthRevenue")));
        dto.setPendingAmount(toBigDecimal(stats.get("pendingAmount")));
        dto.setSettledAmount(toBigDecimal(stats.get("settledAmount")));

        int offset = (pageNum - 1) * pageSize;
        List<Map<String, Object>> dailyList = billDailyRevenueMapper.selectAdminDailyRevenues(monthStart, monthEnd, userId, status != null ? status.getCode() : null, offset, pageSize);
        List<DailyRevenueDTO> dailyDTOs = new ArrayList<>();
        List<Long> userIds = new ArrayList<>();
        for (Map<String, Object> row : dailyList) {
            DailyRevenueDTO daily = new DailyRevenueDTO();
            daily.setDt((String) row.get("dt"));
            daily.setAmount(toBigDecimal(row.get("amount")));
            daily.setStatus(RevenueStatusEnum.fromCode((String) row.get("status")));
            daily.setUserId(toLong(row.get("user_id")));
            dailyDTOs.add(daily);
            userIds.add(daily.getUserId());
        }
        dto.setDailyRevenues(dailyDTOs);

        List<Map<String, Object>> rankings = billDailyRevenueMapper.selectUserRankings(monthStart, monthEnd, userId);
        Long total = billDailyRevenueMapper.countAdminDailyRevenues(monthStart, monthEnd, userId, status != null ? status.getCode() : null);

        List<RevenueStatsDTO.UserRevenueRank> userRanks = new ArrayList<>();
        for (Map<String, Object> row : rankings) {
            RevenueStatsDTO.UserRevenueRank rank = new RevenueStatsDTO.UserRevenueRank();
            rank.setUserId(toLong(row.get("userId")));
            rank.setAmount(toBigDecimal(row.get("amount")));
            userRanks.add(rank);
            userIds.add(rank.getUserId());
        }
        dto.setUserRankings(userRanks);
        dto.setTotal(total);
        dto.setPageNum(pageNum);
        dto.setPageSize(pageSize);

        //补充用户信息
        List<UserContext> userContexts = iUserRpcService.queryUserListByIds(userIds);
        Map<Long, UserContext> userContextMap = userContexts.stream().collect(Collectors.toMap(UserContext::getUserId, u -> u, (a, b) -> a));
        dailyDTOs.forEach(daily -> {
            UserContext userContext = userContextMap.get(daily.getUserId());
            if (userContext != null) {
                daily.setUserName(userContext.getUserName());
                daily.setNickName(userContext.getNickName());
                daily.setPhone(userContext.getPhone());
                daily.setEmail(userContext.getEmail());
            }
        });
        userRanks.forEach(rank -> {
            UserContext userContext = userContextMap.get(rank.getUserId());
            if (userContext != null) {
                rank.setUserName(StringUtils.isNotBlank(userContext.getNickName()) ? userContext.getNickName() : userContext.getUserName());
            }
        });
        return dto;
    }

    @Override
    public RevenueDetailPageDTO getUserRevenueDetails(Long userId, String dt, Integer pageNum, Integer pageSize) {
        List<BillRevenueDetail> list = billRevenueDetailService.lambdaQuery()
                .eq(BillRevenueDetail::getUserId, userId)
                .eq(dt != null, BillRevenueDetail::getDt, dt)
                .orderByDesc(BillRevenueDetail::getCreated)
                .last("LIMIT " + ((pageNum - 1) * pageSize) + ", " + pageSize)
                .list();
        Long total = billRevenueDetailService.lambdaQuery()
                .eq(BillRevenueDetail::getUserId, userId)
                .eq(dt != null, BillRevenueDetail::getDt, dt)
                .count();

        RevenueDetailPageDTO page = new RevenueDetailPageDTO();
        page.setRecords(list.stream().map(this::convertDetailToDTO).collect(Collectors.toList()));
        page.setTotal(total);
        page.setPageNum(pageNum);
        page.setPageSize(pageSize);
        return page;
    }

    private DailyRevenueDTO convertDailyToDTO(BillDailyRevenue entity) {
        DailyRevenueDTO dto = new DailyRevenueDTO();
        BeanUtils.copyProperties(entity, dto);
        dto.setStatus(RevenueStatusEnum.fromCode(entity.getStatus()));
        return dto;
    }

    private RevenueDetailDTO convertDetailToDTO(BillRevenueDetail entity) {
        RevenueDetailDTO dto = new RevenueDetailDTO();
        BeanUtils.copyProperties(entity, dto);
        dto.setType(RevenueTypeEnum.fromCode(entity.getType()));
        dto.setTargetType(RevenueTargetTypeEnum.fromCode(entity.getTargetType()));
        if (entity.getExtra() != null) {
            try {
                dto.setExtra(JSON.parseObject(entity.getExtra()));
            } catch (Exception ignored) {
            }
        }
        return dto;
    }

    private Long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Long) return (Long) val;
        if (val instanceof Number) return ((Number) val).longValue();
        return Long.parseLong(val.toString());
    }

    private BigDecimal toBigDecimal(Object val) {
        if (val == null) return BigDecimal.ZERO;
        BigDecimal result;
        if (val instanceof BigDecimal) result = (BigDecimal) val;
        else if (val instanceof Number) result = BigDecimal.valueOf(((Number) val).doubleValue());
        else result = new BigDecimal(val.toString());
        return result.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
