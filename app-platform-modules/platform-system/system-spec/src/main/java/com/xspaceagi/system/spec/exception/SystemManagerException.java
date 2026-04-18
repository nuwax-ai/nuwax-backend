package com.xspaceagi.system.spec.exception;

/**
 * 系统管理异常,如人员,机构,角色等管理异常
 */
public class SystemManagerException extends KindlyException {

    /**
     * 默认配置前缀
     */
    private static final String DISPLAY_CODE = "SystemManager";


    public SystemManagerException(String code, String message) {
        super(code, message);
    }


    /**
     * 根据定义好的异常枚举,构建异常信息
     *
     * @param codeEnum 异常枚举
     * @param params   占位参数
     * @return 异常
     */
    public static SystemManagerException build(BizExceptionCodeEnum codeEnum, Object... params) {
        String errorMessage = codeEnum.formatMessage(params);
        return new SystemManagerException(codeEnum.getCode(), errorMessage);
    }

    @Override
    public String getModule() {
        return DISPLAY_CODE;
    }
}
