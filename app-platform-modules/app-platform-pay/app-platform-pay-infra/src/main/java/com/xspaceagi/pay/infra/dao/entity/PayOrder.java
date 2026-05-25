package com.xspaceagi.pay.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.xspaceagi.pay.spec.enums.PayBizNotifyStatus;
import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.sdk.enums.PayMode;
import com.xspaceagi.pay.spec.enums.PayOrderGatewaySyncStatus;
import java.util.Date;
import lombok.Data;

@Data
@TableName("pay_order")
public class PayOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("_tenant_id")
    private Long tenantId;

    private String bizOrderNo;

    private String bizScene;

    private Long orderAmount;

    private String subject;

    private String ext;

    private PayMode payMode;

    private PayChannel payChannel;

    /** 分 */
    private Long platformFee;

    /** 分 */
    private Long providerFee;

    /** 分 */
    private Long netAmount;

    private String gatewayPaymentOrderNo;

    private PayOrderGatewaySyncStatus gatewaySyncStatus;

    private PayBizNotifyStatus bizNotifyStatus;

    private String gatewayLastError;

    private String gatewayOrderStatus;

    private Date paidAt;

    private Date created;

    private Date modified;
}
