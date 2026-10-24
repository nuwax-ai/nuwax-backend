package com.xspaceagi.pay.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

/** 开放支付 webhook 通知任务：一单一任务（gateway_order_no 唯一），PAID 后带签名回调，有限重试 */
@Data
@TableName("pay_open_notify_task")
public class PayOpenNotifyTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("_tenant_id")
    private Long tenantId;

    /** pay_order 主键 */
    private Long orderId;

    private String gatewayOrderNo;

    private String bizOrderNo;

    /** 接入应用（user_project 主键） */
    private String openProjectId;

    /** 三方通知地址 */
    private String notifyUrl;

    /** 待发送报文（JSON，含签名原料字段） */
    private String payload;

    /** 签名密钥快照（下单 AK 的 accessKey）：HMAC-SHA256(payload) 用 */
    private String signKeySnapshot;

    /** WAIT_PAY-待支付 / PENDING-待发送(可重试) / SUCCESS / FAILED(重试耗尽) / CANCELED(订单关闭) */
    private String status;

    private Integer retryCount;

    private Date nextRetryTime;

    private String lastError;

    private Date created;

    private Date modified;
}
