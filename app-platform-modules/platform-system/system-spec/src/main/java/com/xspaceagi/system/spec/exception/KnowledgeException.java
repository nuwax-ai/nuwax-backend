package com.xspaceagi.system.spec.exception;

/**
 * 知识库异常
 */

public class KnowledgeException extends KindlyException {

    /**
     * 默认配置前缀
     */
    private static final String DISPLAY_CODE = "Knowledge";


    public KnowledgeException(String code, String message) {
        super(code, message);
    }

    public KnowledgeException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }


    /**
     * 根据定义好的异常枚举,构建异常信息
     *
     * @param codeEnum 异常枚举
     * @param params   占位参数
     * @return 异常
     */
    public static KnowledgeException build(BizExceptionCodeEnum codeEnum, Object... params) {
        String errorMessage = codeEnum.formatMessage(params);
        return new KnowledgeException(codeEnum.getCode(), errorMessage);
    }

    /**
     * 根据定义好的异常枚举,构建异常信息（带原始异常）
     *
     * @param codeEnum 异常枚举
     * @param cause    原始异常
     * @return 异常
     */
    public static KnowledgeException build(BizExceptionCodeEnum codeEnum, Throwable cause) {
        return new KnowledgeException(codeEnum.getCode(), codeEnum.formatMessage(), cause);
    }

    @Override
    public String getModule() {
        return DISPLAY_CODE;
    }
}
