package com.xspaceagi.bill.infra.dao.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.bill.infra.dao.entity.BillWithdrawApplication;
import com.xspaceagi.bill.infra.dao.mapper.BillWithdrawApplicationMapper;
import com.xspaceagi.bill.infra.dao.service.IBillWithdrawApplicationService;
import org.springframework.stereotype.Service;

@Service
public class BillWithdrawApplicationServiceImpl extends ServiceImpl<BillWithdrawApplicationMapper, BillWithdrawApplication> implements IBillWithdrawApplicationService {
}
