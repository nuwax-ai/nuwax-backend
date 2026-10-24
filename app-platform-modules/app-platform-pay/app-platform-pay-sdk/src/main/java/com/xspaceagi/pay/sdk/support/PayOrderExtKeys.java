package com.xspaceagi.pay.sdk.support;

/** pay_order.ext / Bill order extra 中与支付相关的扩展键。 */
public final class PayOrderExtKeys {

    public static final String PAY_CLIENT_SCENE = "payClientScene";

    public static final String GENERAL_PROJECT_ID = "generalProjectId";
    public static final String GENERAL_PROJECT_USER_ID = "generalProjectUserId";

    /** 开放接口（/api/v1/pay/open）：接入应用 = user_project 主键 id */
    public static final String OPEN_PROJECT_ID = "openProjectId";
    /** 开放接口：接入方支付结果通知地址（webhook），下单时传入 */
    public static final String OPEN_NOTIFY_URL = "openNotifyUrl";

    private PayOrderExtKeys() {}
}
