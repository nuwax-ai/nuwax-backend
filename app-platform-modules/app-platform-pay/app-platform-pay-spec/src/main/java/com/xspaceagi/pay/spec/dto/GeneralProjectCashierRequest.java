package com.xspaceagi.pay.spec.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用项目支付 - 收银台模式请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneralProjectCashierRequest {

    /** 项目 ID（必传），前端从 DEV_PROJECT_ID 环境变量获取 */
    private String projectId;

    /** 业务订单号，可空；传了用 GP1_ 前缀（幂等），不传用 GP2_ 前缀自动生成 */
    private String bizOrderNo;

    /** 订单金额（单位：分） */
    private Long orderAmount;

    /** 订单标题/商品描述 */
    private String subject;

    /** 支付完成后前端回跳地址，可空 */
    private String frontNotifyUrl;
}
