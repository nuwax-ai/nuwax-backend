package com.xspaceagi.system.spec.exception;

/**
 * 生态市场异常
 */

public class EcoMarketException extends KindlyException {

    /**
     * 默认配置前缀
     */
    private static final String DISPLAY_CODE = "EcoMarket";


    public EcoMarketException(String code, String message) {
        super(code, message);
    }


    /**
     * 根据定义好的异常枚举,构建异常信息
     *
     * @param codeEnum 异常枚举
     * @param params   占位参数
     * @return 异常
     */
    public static EcoMarketException build(BizExceptionCodeEnum codeEnum, Object... params) {
        String errorMessage = codeEnum.formatMessage(params);
        return new EcoMarketException(codeEnum.getCode(), errorMessage);
    }

    @Override
    public String getModule() {
        return DISPLAY_CODE;
    }
}
