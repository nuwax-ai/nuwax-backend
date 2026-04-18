package com.xspaceagi.system.spec.exception;

/**
 * 知识库异常
 */

public class ComposeException extends KindlyException {

    /**
     * 默认配置前缀
     */
    private static final String DISPLAY_CODE = "Compose";


    public ComposeException(String code, String message) {
        super(code, message);
    }


    /**
     * 根据定义好的异常枚举,构建异常信息
     *
     * @param codeEnum 异常枚举
     * @param params   占位参数
     * @return 异常
     */
    public static ComposeException build(BizExceptionCodeEnum codeEnum, Object... params) {
        String errorMessage = codeEnum.formatMessage(params);
        return new ComposeException(codeEnum.getCode(), errorMessage);
    }

    @Override
    public String getModule() {
        return DISPLAY_CODE;
    }
}
