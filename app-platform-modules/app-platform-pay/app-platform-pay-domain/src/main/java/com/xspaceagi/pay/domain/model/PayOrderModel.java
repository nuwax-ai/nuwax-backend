package com.xspaceagi.pay.domain.model;

import com.xspaceagi.pay.spec.enums.PayBizNotifyStatus;
import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.sdk.enums.PayMode;
import com.xspaceagi.pay.spec.enums.PayOrderGatewaySyncStatus;
import java.util.Date;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayOrderModel {

    private Long id;
    private Long tenantId;
    private String bizOrderNo;
    private String bizScene;
    private Long orderAmount;
    private String subject;
    private String ext;
    private PayMode payMode;
    private PayChannel payChannel;
    private Long platformFee;
    private Long providerFee;
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
