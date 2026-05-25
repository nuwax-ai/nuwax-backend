package com.xspaceagi.bill.infra.dao.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.bill.infra.dao.entity.BillRevenueDetail;
import com.xspaceagi.bill.infra.dao.mapper.BillRevenueDetailMapper;
import com.xspaceagi.bill.infra.dao.service.IBillRevenueDetailService;
import org.springframework.stereotype.Service;

@Service
public class BillRevenueDetailServiceImpl extends ServiceImpl<BillRevenueDetailMapper, BillRevenueDetail> implements IBillRevenueDetailService {
}
