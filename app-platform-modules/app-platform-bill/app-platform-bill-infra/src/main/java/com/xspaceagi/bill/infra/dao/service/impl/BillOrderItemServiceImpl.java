package com.xspaceagi.bill.infra.dao.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.bill.infra.dao.entity.BillOrderItem;
import com.xspaceagi.bill.infra.dao.mapper.BillOrderItemMapper;
import com.xspaceagi.bill.infra.dao.service.IBillOrderItemService;
import org.springframework.stereotype.Service;

@Service
public class BillOrderItemServiceImpl extends ServiceImpl<BillOrderItemMapper, BillOrderItem> implements IBillOrderItemService {
}
