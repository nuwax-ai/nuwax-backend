package com.xspaceagi.bill.app.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.dto.AgentInfoDto;
import com.xspaceagi.agent.core.sdk.dto.ReqResult;
import com.xspaceagi.agent.core.sdk.dto.SkillInfoDto;
import com.xspaceagi.bill.app.service.BillOrderAppService;
import com.xspaceagi.bill.app.service.BillRevenueAppService;
import com.xspaceagi.bill.infra.dao.entity.BillOrder;
import com.xspaceagi.bill.infra.dao.entity.BillOrderItem;
import com.xspaceagi.bill.infra.dao.mapper.BillOrderMapper;
import com.xspaceagi.bill.infra.dao.service.IBillOrderItemService;
import com.xspaceagi.bill.infra.dao.service.IBillOrderService;
import com.xspaceagi.bill.sdk.dto.*;
import com.xspaceagi.bill.spec.enums.*;
import com.xspaceagi.credit.sdk.dto.CreditAddRequest;
import com.xspaceagi.credit.sdk.rpc.ICreditRpcService;
import com.xspaceagi.credit.spec.enums.CreditTypeEnum;
import com.xspaceagi.pay.sdk.dto.PaymentOrderCreateResponse;
import com.xspaceagi.pay.sdk.dto.ScanOrderCreateRequest;
import com.xspaceagi.pay.sdk.enums.PayMode;
import com.xspaceagi.pay.sdk.service.IPaymentRpcService;
import com.xspaceagi.subscription.sdk.dto.CreateSubscriptionRequest;
import com.xspaceagi.subscription.sdk.dto.PlanDTO;
import com.xspaceagi.subscription.sdk.rpc.ISubscriptionRpcService;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.I18nUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.time.DateUtils.addMonths;

@Slf4j
@Service
public class BillOrderAppServiceImpl implements BillOrderAppService {

    @Resource
    private IBillOrderService billOrderService;

    @Resource
    private IBillOrderItemService billOrderItemService;

    @Resource
    private BillOrderMapper billOrderMapper;

    @Resource
    private ISubscriptionRpcService iSubscriptionRpcService;

    @Resource
    private BillRevenueAppService billRevenueAppService;

    @Resource
    private IAgentRpcService iAgentRpcService;

    @Resource
    private ICreditRpcService iCreditRpcService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private IPaymentRpcService iPaymentRpcService;

    @Resource
    private UserApplicationService userApplicationService;

    @Override
    public OrderDTO createOrder(CreateOrderRequest request) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), I18nUtil.systemMessage("Backend.Bill.Order.Validate.ItemsEmpty"));
        }

        BillOrder order = BillOrder.builder()
                .tenantId(request.getTenantId())
                .userId(request.getUserId())
                .description(request.getDescription())
                .bizType(request.getBizType() != null ? request.getBizType().getCode() : null)
                .orderStatus(OrderStatusEnum.PENDING.getCode())
                .payStatus(PayStatusEnum.PENDING.getCode())
                .amount(BigDecimal.ZERO)
                .extra(request.getExtra() != null ? JSON.toJSONString(request.getExtra()) : null)
                .created(new Date())
                .build();
        billOrderService.save(order);

        List<BillOrderItem> items = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CreateOrderRequest.CreateOrderItem itemReq : request.getItems()) {
            BigDecimal itemPrice = itemReq.getPrice() != null ? itemReq.getPrice() : BigDecimal.ZERO;
            int count = itemReq.getCount() != null ? itemReq.getCount() : 1;
            BigDecimal lineTotal = itemPrice.multiply(BigDecimal.valueOf(count));

            BillOrderItem item = BillOrderItem.builder()
                    .orderId(order.getId())
                    .targetType(itemReq.getTargetType())
                    .targetName(itemReq.getTargetName())
                    .targetId(itemReq.getTargetId())
                    .price(itemPrice)
                    .count(count)
                    .snapshot(itemReq.getSnapshot() != null ? JSON.toJSONString(itemReq.getSnapshot()) : null)
                    .build();
            items.add(item);
            totalAmount = totalAmount.add(lineTotal);
        }
        try {
            billOrderItemService.saveBatch(items);
            order.setAmount(totalAmount);

            //创建支付订单
            ScanOrderCreateRequest paymentScanCreateOrderRequest = new ScanOrderCreateRequest();
            paymentScanCreateOrderRequest.setBizOrderNo(order.getId().toString());
            paymentScanCreateOrderRequest.setOrderAmount(totalAmount.multiply(new BigDecimal(100)).longValue());
            paymentScanCreateOrderRequest.setSubject(request.getDescription());
            PaymentOrderCreateResponse orderForScan = iPaymentRpcService.createOrderForScan(paymentScanCreateOrderRequest);
            Map<String, Object> extra = request.getExtra();
            if (extra == null) {
                extra = new HashMap<>();
            }
            extra.put("gatewayPaymentOrderNo", orderForScan.getGatewayPaymentOrderNo());
            extra.put("payMode", PayMode.scan.name());
            order.setExtra(JSON.toJSONString(extra));
            billOrderService.updateById(order);
        } catch (Exception e) {
            //有远程网络调用，不使用事务
            billOrderService.removeById(order.getId());
            billOrderItemService.remove(new QueryWrapper<BillOrderItem>().eq("order_id", order.getId()));
            throw new BizException(e.getMessage());
        }

        log.info("创建订单, orderId={}, userId={}, amount={}", order.getId(), request.getUserId(), totalAmount);
        return convertToDTO(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean paymentCallback(Long tenantId, Long orderId, String payStatus) {
        log.info("[payment-callback] tenantId={} orderId={}, payStatus={}", tenantId, orderId, payStatus);
        BillOrder order = billOrderService.getById(orderId);
        if (order == null) {
            throw new BizException("ORDER_NOT_FOUND", "Order does not exist");
        }

        PayStatusEnum payStatusEnum = PayStatusEnum.fromCode(payStatus);
        if (payStatusEnum == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "Invalid payment status");
        }

        if (order.getPayStatus().equals(PayStatusEnum.SUCCESS.getCode())) {
            return true;
        }

        UserDto userDto = userApplicationService.queryById(order.getUserId());
        if (userDto == null) {
            throw new BizException("USER_NOT_FOUND", "User does not exist");
        }
        RequestContext.get().setLangMap(userDto.getLangMap());
        if (PayStatusEnum.SUCCESS.equals(payStatusEnum)) {
            order.setOrderStatus(OrderStatusEnum.PAID.getCode());
            order.setPayStatus(PayStatusEnum.SUCCESS.getCode());
            //支付成功，处理后续业务
            OrderDTO orderDTO = convertToDTO(order);
            if (order.getBizType().equals(BizTypeEnum.SUBSCRIPTION.getCode())) {
                PlanDTO plan = iSubscriptionRpcService.getPlan(orderDTO.getItems().get(0).getTargetId());
                if (plan.getBizType() == com.xspaceagi.subscription.spec.enums.BizTypeEnum.AGENT && plan.getPrice().compareTo(BigDecimal.ZERO) > 0) {
                    ReqResult<AgentInfoDto> agentInfoDtoReqResult = iAgentRpcService.queryPublishedAgentInfo(Long.parseLong(plan.getBizId()));
                    if (agentInfoDtoReqResult.getData() != null) {
                        AddRevenueRequest addRevenueRequest = new AddRevenueRequest();
                        addRevenueRequest.setTenantId(tenantId);
                        addRevenueRequest.setUserId(agentInfoDtoReqResult.getData().getCreatorId());
                        addRevenueRequest.setBizNo("ORDER" + orderId);
                        addRevenueRequest.setAmount(orderDTO.getAmount());
                        addRevenueRequest.setType(RevenueTypeEnum.PLAN);
                        addRevenueRequest.setTypeId(plan.getId());
                        addRevenueRequest.setOrderId(orderId);
                        addRevenueRequest.setTargetType(RevenueTargetTypeEnum.AGENT);
                        addRevenueRequest.setTargetId(agentInfoDtoReqResult.getData().getId());
                        addRevenueRequest.setRemark(agentInfoDtoReqResult.getData().getName() + "-" + plan.getName());
                        billRevenueAppService.addRevenue(addRevenueRequest);
                        log.info("订单支付成功，添加收益, orderId={}, agentId={}, amount={}", orderId, agentInfoDtoReqResult.getData().getId(), orderDTO.getAmount());
                    }
                } else if (plan.getBizType() == com.xspaceagi.subscription.spec.enums.BizTypeEnum.SKILL && plan.getPrice().compareTo(BigDecimal.ZERO) > 0) {
                    ReqResult<SkillInfoDto> publishedSkillInfo = iAgentRpcService.getPublishedSkillInfo(Long.parseLong(plan.getBizId()), null);
                    if (publishedSkillInfo.getData() != null) {
                        AddRevenueRequest addRevenueRequest = new AddRevenueRequest();
                        addRevenueRequest.setTenantId(tenantId);
                        addRevenueRequest.setUserId(publishedSkillInfo.getData().getCreatorId());
                        addRevenueRequest.setBizNo("ORDER" + orderId);
                        addRevenueRequest.setAmount(orderDTO.getAmount());
                        addRevenueRequest.setType(RevenueTypeEnum.PLAN);
                        addRevenueRequest.setTypeId(plan.getId());
                        addRevenueRequest.setOrderId(orderId);
                        addRevenueRequest.setTargetType(RevenueTargetTypeEnum.SKILL);
                        addRevenueRequest.setTargetId(publishedSkillInfo.getData().getId());
                        addRevenueRequest.setRemark(publishedSkillInfo.getData().getName());
                        billRevenueAppService.addRevenue(addRevenueRequest);
                        log.info("订单支付成功，添加收益, orderId={}, skillId={}, amount={}", orderId, publishedSkillInfo.getData().getId(), orderDTO.getAmount());
                    }
                }
                CreateSubscriptionRequest createSubscriptionRequest = new CreateSubscriptionRequest();
                createSubscriptionRequest.setTenantId(tenantId);
                createSubscriptionRequest.setUserId(orderDTO.getUserId());
                createSubscriptionRequest.setPlanId(plan.getId());
                createSubscriptionRequest.setBizType(plan.getBizType());
                createSubscriptionRequest.setExtra(Map.of("plan", plan));
                iSubscriptionRpcService.createSubscription(createSubscriptionRequest);
                log.info("订单支付成功，创建订阅, orderId={}, planId={}, userId={}", orderId, plan.getId(), orderDTO.getUserId());
            }
            if (order.getBizType().equals(BizTypeEnum.CREDIT_PURCHASE.getCode())) {
                Map<String, Object> snapshot = orderDTO.getItems().get(0).getSnapshot();
                if (snapshot != null && snapshot.containsKey("creditAmount")) {
                    TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(tenantId);
                    CreditAddRequest creditAddRequest = new CreditAddRequest();
                    creditAddRequest.setTenantId(tenantId);
                    creditAddRequest.setUserId(orderDTO.getUserId());
                    if (snapshot.get("period") != null) {
                        creditAddRequest.setExpireTime(addMonths(new Date(), Integer.parseInt(snapshot.get("period").toString())));
                    }
                    creditAddRequest.setAmount(new BigDecimal(snapshot.get("creditAmount").toString()));
                    creditAddRequest.setCreditType(CreditTypeEnum.PURCHASE);
                    creditAddRequest.setRemark(I18nUtil.systemMessage("Backend.Bill.Order.CreditPurchaseRemark", String.valueOf(snapshot.get("packageName"))));
                    creditAddRequest.setBizNo("ORDER" + orderId);
                    creditAddRequest.setExtra(Map.of("orderId", orderId, "price", orderDTO.getAmount(), "creditExchangeRate", tenantConfig.getCreditExchangeRate()));
                    iCreditRpcService.addCredit(creditAddRequest);
                    log.info("订单支付成功，添加积分, orderId={}, amount={}", orderId, snapshot.get("creditAmount"));
                }
            }

        } else if (PayStatusEnum.FAILED.equals(payStatusEnum) || PayStatusEnum.CLOSED.equals(payStatusEnum)) {
            order.setOrderStatus(OrderStatusEnum.CANCELLED.getCode());
            order.setPayStatus(payStatusEnum.getCode());
        } else {
            order.setPayStatus(payStatusEnum.getCode());
        }

        billOrderService.updateById(order);
        log.info("支付回调, orderId={}, payStatus={}", orderId, payStatus);
        return true;
    }

    @Override
    public OrderPageDTO queryOrders(OrderQueryRequest query) {
        int pageNum = query.getPageNum() != null ? query.getPageNum() : 1;
        int pageSize = query.getPageSize() != null ? query.getPageSize() : 20;
        int offset = (pageNum - 1) * pageSize;
        List<BillOrder> orders = billOrderMapper.selectListWithFilters(query, offset, pageSize);
        Long total = billOrderMapper.countWithFilters(query);

        OrderPageDTO page = new OrderPageDTO();
        page.setRecords(orders.stream().map(this::convertToDTO).collect(Collectors.toList()));
        page.setTotal(total);
        page.setPageNum(pageNum);
        page.setPageSize(pageSize);
        return page;
    }

    @Override
    public OrderDTO queryOrder(Long orderId) {
        return convertToDTO(billOrderService.getById(orderId));
    }

    @Override
    public OrderSettlementStatusResponse getOrderSettlementStatus(long userId, Long orderId) {
        if (orderId == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), I18nUtil.systemMessage("Backend.Bill.Order.Validate.OrderIdRequired"));
        }
        BillOrder order = billOrderService.getById(orderId);
        if (order == null) {
            throw new BizException("ORDER_NOT_FOUND", I18nUtil.systemMessage("Backend.Bill.Order.Error.OrderNotFound"));
        }
        if (!Long.valueOf(userId).equals(order.getUserId())) {
            throw new BizException("USER_NOT_MATCH", I18nUtil.systemMessage("Backend.Bill.Order.Error.UserNotMatch"));
        }
        PayStatusEnum currentPayStatus = PayStatusEnum.fromCode(order.getPayStatus());
        if (!isTerminalPayStatus(currentPayStatus)) {
            try {
                iPaymentRpcService.syncSettlementForBizOrderNo(String.valueOf(orderId));
            } catch (Exception e) {
                log.warn("[settlement-status] sync pay status failed orderId={} msg={}", orderId, e.getMessage());
            }
            order = billOrderService.getById(orderId);
        }
        OrderDTO dto = convertToDTO(order);
        PayStatusEnum payStatus = dto.getPayStatus();
        boolean settled = PayStatusEnum.SUCCESS == payStatus;
        boolean terminalFailed = PayStatusEnum.FAILED == payStatus || PayStatusEnum.CLOSED == payStatus;
        String message;
        if (settled) {
            message = I18nUtil.systemMessage("Backend.Bill.Order.Status.PaySuccess");
        } else if (terminalFailed) {
            message = payStatus != null ? payStatus.getDesc() : I18nUtil.systemMessage("Backend.Bill.Order.Status.PayIncomplete");
        } else {
            message = I18nUtil.systemMessage("Backend.Bill.Order.Status.SyncingPayment");
        }
        return OrderSettlementStatusResponse.builder()
                .orderId(orderId)
                .payStatus(payStatus)
                .orderStatus(dto.getOrderStatus())
                .settled(settled)
                .terminalFailed(terminalFailed)
                .message(message)
                .build();
    }

    private static boolean isTerminalPayStatus(PayStatusEnum payStatus) {
        return payStatus == PayStatusEnum.SUCCESS
                || payStatus == PayStatusEnum.FAILED
                || payStatus == PayStatusEnum.CLOSED;
    }

    private OrderDTO convertToDTO(BillOrder entity) {
        if (entity == null) {
            return null;
        }
        OrderDTO dto = new OrderDTO();
        BeanUtils.copyProperties(entity, dto);
        dto.setBizType(BizTypeEnum.fromCode(entity.getBizType()));
        dto.setOrderStatus(OrderStatusEnum.fromCode(entity.getOrderStatus()));
        dto.setPayStatus(PayStatusEnum.fromCode(entity.getPayStatus()));
        if (entity.getExtra() != null) {
            try {
                dto.setExtra(JSON.parseObject(entity.getExtra()));
            } catch (Exception ignored) {
            }
        }

        List<BillOrderItem> items = billOrderItemService.lambdaQuery()
                .eq(BillOrderItem::getOrderId, entity.getId())
                .list();
        dto.setItems(items.stream().map(item -> {
            OrderItemDTO itemDTO = new OrderItemDTO();
            BeanUtils.copyProperties(item, itemDTO);
            itemDTO.setTargetType(TargetTypeEnum.fromCode(item.getTargetType()));
            if (item.getSnapshot() != null) {
                try {
                    itemDTO.setSnapshot(JSON.parseObject(item.getSnapshot()));
                } catch (Exception ignored) {
                }
            }
            return itemDTO;
        }).collect(Collectors.toList()));
        //超过24小时则改变状态为关闭
        if (entity.getCreated().getTime() + 24 * 60 * 60 * 1000 < System.currentTimeMillis() && entity.getOrderStatus().equals(OrderStatusEnum.PENDING.getCode())) {
            dto.setOrderStatus(OrderStatusEnum.CANCELLED);
            dto.setPayStatus(PayStatusEnum.CLOSED);
            BillOrder billOrder = new BillOrder();
            billOrder.setId(entity.getId());
            billOrder.setOrderStatus(OrderStatusEnum.CANCELLED.getCode());
            billOrder.setPayStatus(PayStatusEnum.CLOSED.getCode());
            billOrderService.updateById(billOrder);
        }
        return dto;
    }
}
