package com.xspaceagi.bill.infra.dao.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.bill.infra.dao.entity.BillDailyRevenue;
import com.xspaceagi.bill.infra.dao.mapper.BillDailyRevenueMapper;
import com.xspaceagi.bill.infra.dao.service.IBillDailyRevenueService;
import org.springframework.stereotype.Service;

@Service
public class BillDailyRevenueServiceImpl extends ServiceImpl<BillDailyRevenueMapper, BillDailyRevenue> implements IBillDailyRevenueService {
}
