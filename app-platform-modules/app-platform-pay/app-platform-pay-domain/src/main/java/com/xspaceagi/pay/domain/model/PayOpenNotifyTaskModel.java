package com.xspaceagi.pay.domain.model;

import java.util.Date;
import lombok.Data;

/** 开放支付 webhook 通知任务领域模型 */
@Data
public class PayOpenNotifyTaskModel {

    private Long id;

    private Long tenantId;

    private Long orderId;

    private String gatewayOrderNo;

    private String bizOrderNo;

    private String openProjectId;

    private String notifyUrl;

    private String payload;

    private String signKeySnapshot;

    /** NOTIFY_WAIT_PAY / NOTIFY_RETRY / NOTIFY_SUCCESS / NOTIFY_FAILED / NOTIFY_CANCELED */
    private String status;

    private Integer retryCount;

    private Date nextRetryTime;

    private String lastError;

    private Date created;

    private Date modified;

    public static final String NOTIFY_WAIT_PAY = "NOTIFY_WAIT_PAY";
    public static final String NOTIFY_RETRY = "NOTIFY_RETRY";
    public static final String NOTIFY_SUCCESS = "NOTIFY_SUCCESS";
    public static final String NOTIFY_FAILED = "NOTIFY_FAILED";
    public static final String NOTIFY_CANCELED = "NOTIFY_CANCELED";
}
