package com.xspaceagi.pay.application.support;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.pay.domain.model.PayOpenNotifyTaskModel;
import com.xspaceagi.pay.domain.model.PayOrderModel;
import com.xspaceagi.pay.domain.repository.PayOpenNotifyTaskRepository;
import com.xspaceagi.pay.domain.repository.PayOrderRepository;
import com.xspaceagi.pay.sdk.enums.PaymentStatus;
import com.xspaceagi.pay.sdk.support.PayOrderExtKeys;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 开放支付 webhook：下单建任务 → PAID 后带签名回调 → 有限重试（1m/5m/30m/2h/6h 共 5 次）。
 *
 * <p>签名：X-Nuwax-Signature: sha256=HMAC_SHA256(rawPayload, 下单AK的accessKey)。
 * 三方用其 AK 验签（AK 仅持有者可知，无 AK 造不出有效签名）；通知可能重发，三方须按 gatewayOrderNo 幂等。
 *
 * <p>成功标准：HTTP 200。调不通不阻塞支付主链路——重试耗尽标 FAILED，
 * 三方可随时用查单接口补拉（查单是唯一真相源，通知只是加速器）。
 */
@Slf4j
@Service
public class OpenPayNotifyService {

    /** 退避间隔（分钟）：重试 5 次后放弃 */
    private static final long[] RETRY_DELAY_MINUTES = {1, 5, 30, 120, 360};

    private static final int MAX_SEND_ATTEMPTS = RETRY_DELAY_MINUTES.length + 1;

    private final PayOpenNotifyTaskRepository notifyTaskRepository;
    private final PayOrderRepository payOrderRepository;
    private final RestTemplate restTemplate;

    public OpenPayNotifyService(PayOpenNotifyTaskRepository notifyTaskRepository,
                                PayOrderRepository payOrderRepository) {
        this.notifyTaskRepository = notifyTaskRepository;
        this.payOrderRepository = payOrderRepository;
        // per-connection 禁 redirect（302→内网 = SSRF 绕过）；不动 JVM 全局（会影响 IM/网关等客户端）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 下单入口：按业务单号定位订单后建任务（controller 只持有单号时用）。
     * ext 无 OPEN_NOTIFY_URL 时静默跳过（三方未订阅通知）。
     */
    public void createTaskForBizOrderNo(long tenantId, String bizOrderNo) {
        PayOrderModel order = TenantFunctions.callWithIgnoreCheck(() ->
                payOrderRepository.findByTenantIdAndBizOrderNo(tenantId, bizOrderNo).orElse(null));
        if (order != null) {
            createTask(order);
        }
    }

    /**
     * 下单后建通知任务（幂等：同 gatewayOrderNo 只建一次）。
     * 签名密钥取下单 AK 的 accessKey 快照（AK 轮换后旧任务仍可按快照验签）。
     */
    public void createTask(PayOrderModel order) {
        if (order == null || order.getTenantId() == null) {
            return;
        }
        String notifyUrl = readExtField(order.getExt(), PayOrderExtKeys.OPEN_NOTIFY_URL);
        if (notifyUrl == null || notifyUrl.isBlank()) {
            return; // 未传 notifyUrl：三方自行轮询
        }
        final String openProjectId = readExtField(order.getExt(), PayOrderExtKeys.OPEN_PROJECT_ID);
        final String signKey;
        if (RequestContext.get() != null && RequestContext.get().getUserAccessKey() instanceof UserAccessKeyDto ak && ak.getAccessKey() != null) {
            signKey = ak.getAccessKey();
        } else {
            signKey = "";
        }

        TenantFunctions.callWithIgnoreCheck(() -> {
            try {
                if (notifyTaskRepository.findByTenantIdAndGatewayOrderNo(order.getTenantId(), order.getGatewayPaymentOrderNo()).isPresent()) {
                    return null; // 幂等
                }
            PayOpenNotifyTaskModel task = new PayOpenNotifyTaskModel();
            task.setTenantId(order.getTenantId());
            task.setOrderId(order.getId());
            task.setGatewayOrderNo(order.getGatewayPaymentOrderNo());
            task.setBizOrderNo(order.getBizOrderNo());
            task.setOpenProjectId(openProjectId);
            task.setNotifyUrl(notifyUrl);
            task.setSignKeySnapshot(signKey);
            task.setStatus(PayOpenNotifyTaskModel.NOTIFY_WAIT_PAY);
            task.setRetryCount(0);
            task.setNextRetryTime(new Date());
            notifyTaskRepository.save(task);
            return null;
            } catch (org.springframework.dao.DuplicateKeyException dup) {
                return null; // 并发插入竞态：另一线程已建，视为幂等成功
            }
        });
        log.info("[pay-open-notify] task created, gatewayOrderNo={}, notifyUrl={}", order.getGatewayPaymentOrderNo(), notifyUrl);
    }

    /**
     * 单个任务 tick：WAIT_PAY 查订单是否 PAID → 发送；PENDING 到期重试。
     */
    public void tick(PayOpenNotifyTaskModel task) {
        PayOrderModel order = TenantFunctions.callWithIgnoreCheck(() ->
                payOrderRepository.findByTenantIdAndGatewayPaymentOrderNo(task.getTenantId(), task.getGatewayOrderNo()).orElse(null));
        if (order == null) {
            markCanceled(task, "order not found");
            return;
        }
        if (PayOpenNotifyTaskModel.NOTIFY_WAIT_PAY.equals(task.getStatus())) {
            String status = order.getGatewayOrderStatus();
            if (status == null || status.isBlank()) {
                return; // 尚未支付，继续等待
            }
            if (PaymentStatus.CLOSED.name().equals(status) || PaymentStatus.FAILED.name().equals(status)) {
                markCanceled(task, "order terminal: " + status);
                return;
            }
            if (!PaymentStatus.PAID.name().equals(status)) {
                return; // 非终态继续等待
            }
        }
        send(task, order);
    }

    /** 发送通知：200=SUCCESS；否则按退避重试，耗尽标 FAILED */
    private void send(PayOpenNotifyTaskModel task, PayOrderModel order) {
        String payload = buildPayload(task, order);
        task.setPayload(payload);
        // send-time 地址校验：内网/环回/CGNAT/ULA 拒绝（DNS rebinding 残余风险由
        // per-connection no-follow 覆盖——302→内网被挡；TLS 校验保留原域名不受影响）
        try {
            URI uri = URI.create(task.getNotifyUrl());
            InetAddress addr = InetAddress.getByName(uri.getHost());
            if (isInternalAddress(addr)) {
                markFailed(task, "notifyUrl resolved to internal address");
                return;
            }
        } catch (java.net.UnknownHostException e) {
            // DNS 暂时失败走重试（同 HTTP 失败），不直接 FAILED——DNS 恢复后仍可送达
            scheduleRetry(task, "DNS resolve failed: " + e.getMessage());
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Nuwax-Signature", "sha256=" + hmacSha256(payload, task.getSignKeySnapshot()));
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    java.net.URI.create(task.getNotifyUrl()), new HttpEntity<>(payload, headers), String.class);
            if (resp.getStatusCode().is2xxSuccessful()) {
                task.setStatus(PayOpenNotifyTaskModel.NOTIFY_SUCCESS);
                task.setLastError(null);
                task.setSignKeySnapshot(""); // AK 凭证不留存（空串=已清；MyBatis-Plus NOT_NULL 策略跳过 null）
                notifyTaskRepository.save(task);
                log.info("[pay-open-notify] delivered, gatewayOrderNo={}, attempt={}", task.getGatewayOrderNo(), task.getRetryCount() + 1);
                return;
            }
            scheduleRetry(task, "HTTP " + resp.getStatusCode().value());
        } catch (Exception e) {
            scheduleRetry(task, e.getMessage());
        }
    }

    private void scheduleRetry(PayOpenNotifyTaskModel task, String error) {
        task.setLastError(error != null && error.length() > 500 ? error.substring(0, 500) : error);
        int nextAttempt = task.getRetryCount() + 1;
        if (nextAttempt >= MAX_SEND_ATTEMPTS) {
            task.setStatus(PayOpenNotifyTaskModel.NOTIFY_FAILED);
            notifyTaskRepository.save(task);
            log.warn("[pay-open-notify] exhausted after {} attempts, gatewayOrderNo={}, notifyUrl={}, lastError={}",
                    nextAttempt, task.getGatewayOrderNo(), task.getNotifyUrl(), task.getLastError());
            return;
        }
        task.setStatus(PayOpenNotifyTaskModel.NOTIFY_RETRY);
        task.setRetryCount(nextAttempt);
        task.setNextRetryTime(new Date(System.currentTimeMillis() + RETRY_DELAY_MINUTES[nextAttempt - 1] * 60_000L));
        notifyTaskRepository.save(task);
    }

    private void markCanceled(PayOpenNotifyTaskModel task, String reason) {
        task.setStatus(PayOpenNotifyTaskModel.NOTIFY_CANCELED);
        task.setLastError(reason);
        notifyTaskRepository.save(task);
    }

    private String buildPayload(PayOpenNotifyTaskModel task, PayOrderModel order) {
        Map<String, Object> body = new HashMap<>();
        body.put("openAppId", task.getOpenProjectId());
        body.put("outTradeNo", task.getBizOrderNo() != null && task.getBizOrderNo().startsWith("OPEN_")
                ? task.getBizOrderNo().substring(("OPEN_" + task.getOpenProjectId() + "_").length()) : null);
        body.put("orderNo", task.getBizOrderNo());
        body.put("gatewayOrderNo", task.getGatewayOrderNo());
        body.put("orderAmount", order.getOrderAmount());
        body.put("status", order.getGatewayOrderStatus());
        body.put("paidAt", order.getPaidAt());
        body.put("notifyType", "PAY_PAID");
        body.put("timestamp", System.currentTimeMillis());
        body.put("nonce", UUID.randomUUID().toString().replace("-", ""));
        return JSON.toJSONString(body);
    }

    /** 内网地址判定：环回/链路本地/私有/任意本地/组播 + CGNAT 100.64/10 + IPv6 ULA */
    static boolean isInternalAddress(java.net.InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }
        byte[] raw = addr.getAddress();
        if (raw.length == 4 && (raw[0] & 0xFF) == 100 && (raw[1] & 0xC0) == 64) {
            return true; // CGNAT
        }
        return raw.length == 16 && (raw[0] & 0xFE) == 0xFC; // IPv6 ULA
    }

    private void markFailed(PayOpenNotifyTaskModel task, String reason) {
        task.setStatus(PayOpenNotifyTaskModel.NOTIFY_FAILED);
        task.setLastError(reason);
        notifyTaskRepository.save(task);
        log.warn("[pay-open-notify] {}", reason);
    }

    public static String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("hmac failed", e);
        }
    }

    private String readExtField(String ext, String key) {
        if (ext == null || ext.isBlank()) {
            return null;
        }
        try {
            Object v = JSON.parseObject(ext).get(key);
            return v != null ? String.valueOf(v) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
