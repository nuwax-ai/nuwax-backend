package com.xspaceagi.bill.infra.dao.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.bill.infra.dao.entity.BillOrder;
import com.xspaceagi.bill.infra.dao.mapper.BillOrderMapper;
import com.xspaceagi.bill.infra.dao.service.IBillOrderService;
import org.springframework.stereotype.Service;

@Service
public class BillOrderServiceImpl extends ServiceImpl<BillOrderMapper, BillOrder> implements IBillOrderService {
}
