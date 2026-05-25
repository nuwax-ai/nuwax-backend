package com.xspaceagi.bill.infra.dao.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.bill.infra.dao.entity.BillWithdrawConfig;
import com.xspaceagi.bill.infra.dao.mapper.BillWithdrawConfigMapper;
import com.xspaceagi.bill.infra.dao.service.IBillWithdrawConfigService;
import org.springframework.stereotype.Service;

@Service
public class BillWithdrawConfigServiceImpl extends ServiceImpl<BillWithdrawConfigMapper, BillWithdrawConfig> implements IBillWithdrawConfigService {
}
