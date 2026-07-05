package com.xspaceagi.pay.spec.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用项目支付 - H5 第二步：调起渠道支付响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneralProjectH5PayResponse {

    /** 业务订单号 */
    private String orderNo;

    /** 网关支付订单号 */
    private String gatewayOrderNo;

    /** 表单 HTML（invokeType=FORM_HTML 时，直接写入页面） */
    private String formHtml;

    /** 跳转地址（invokeType=REDIRECT_URL 时，浏览器跳转） */
    private String redirectUrl;

    /** 调起方式：FORM_HTML / REDIRECT_URL / QRCODE_FALLBACK */
    private String invokeType;

    /** 当前支付单状态 */
    private String status;
}
