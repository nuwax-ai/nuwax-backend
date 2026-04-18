package com.xspaceagi.system.spec.exception;

/**
 * AI Agent 异常
 */
public class AgentException extends KindlyException {

    /**
     * 默认配置前缀
     */
    private static final String DISPLAY_CODE = "Agent";


    public AgentException(String code, String message) {
        super(code, message);
    }


    /**
     * 根据定义好的异常枚举,构建异常信息
     *
     * @param codeEnum 异常枚举
     * @param params   占位参数
     * @return 异常
     */
    public static AgentException build(BizExceptionCodeEnum codeEnum, Object... params) {
        String errorMessage = codeEnum.formatMessage(params);
        return new AgentException(codeEnum.getCode(), errorMessage);
    }

    @Override
    public String getModule() {
        return DISPLAY_CODE;
    }
}
