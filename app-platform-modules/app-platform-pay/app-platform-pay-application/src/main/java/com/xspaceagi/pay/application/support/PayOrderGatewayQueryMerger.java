package com.xspaceagi.pay.application.support;

import com.xspaceagi.pay.domain.model.PayOrderModel;
import com.xspaceagi.pay.sdk.dto.ScanOrderStatusQueryResponse;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class PayOrderGatewayQueryMerger {

    private static final DateTimeFormatter GATEWAY_PAID_AT =
            DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss", Locale.ROOT);

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private PayOrderGatewayQueryMerger() {}

    public static void mergeIntoRow(PayOrderModel row, ScanOrderStatusQueryResponse r) {
        if (r == null) {
            return;
        }
        if (r.getPlatformFee() != null) {
            row.setPlatformFee(r.getPlatformFee());
        }
        if (r.getProviderFee() != null) {
            row.setProviderFee(r.getProviderFee());
        }
        if (r.getNetAmount() != null) {
            row.setNetAmount(r.getNetAmount());
        }
        if (r.getStatus() != null) {
            row.setGatewayOrderStatus(r.getStatus().name());
        }
        if (r.getPayChannel() != null) {
            row.setPayChannel(r.getPayChannel());
        }
        mergePaidAt(row, r.getPaidAt());
    }

    /**
     * 将网关状态查询中的 {@code paidAt}（{@code yyyyMMdd HH:mm:ss}，东八区墙钟）写入订单；空串不覆盖已有值。
     */
    private static void mergePaidAt(PayOrderModel row, String paidAtStr) {
        if (paidAtStr == null || paidAtStr.isBlank()) {
            return;
        }
        try {
            LocalDateTime ldt = LocalDateTime.parse(paidAtStr.trim(), GATEWAY_PAID_AT);
            row.setPaidAt(Date.from(ldt.atZone(SHANGHAI).toInstant()));
        } catch (DateTimeParseException e) {
            log.warn("ignore unparseable paidAt from gateway: {}", paidAtStr);
        }
    }
}
