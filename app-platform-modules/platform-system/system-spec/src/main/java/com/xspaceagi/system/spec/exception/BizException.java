package com.xspaceagi.system.spec.exception;

import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.HttpStatusEnum;

/**
 * 业务异常
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private int status;

    private String code = "0001";

    private String msg;

    /**
     * {@link BizExceptionCodeEnum} 常量名（{@link Enum#name()}，小驼峰），用于响应阶段按语言重算文案。
     * 对外 JSON 的 {@link #getCode()} 可能为 4000/4030 等，与本字段独立。
     */
    private String msgName;

    /**
     * 与 {@link IBizExceptionCodeEnum#formatMessage(Object...)} 一致的占位参数，供全局异常处理按 {@code RequestContext.lang} 重算中英文。
     */
    private Object[] messageFormatArgs;

    public BizException(String msg) {
        super(msg);
        this.msg = msg;
    }

    public BizException(String code, String msg) {
        this(code, msg, null);
    }

    public BizException(String code, String msg, String msgName) {
        this(code, msg, msgName, null);
    }

    public BizException(String code, String msg, String msgName, Object[] messageFormatArgs) {
        super(msg);
        this.code = code;
        this.msg = msg;
        this.msgName = msgName;
        this.messageFormatArgs = messageFormatArgs == null ? null : messageFormatArgs.clone();
        this.status = HttpStatusEnum.OK.code();
    }

    public BizException(HttpStatusEnum status, ErrorCodeEnum code) {
        super(code.getMsg());
        this.code = code.getCode();
        this.msg = code.getMsg();
        this.status = status.code();
    }

    public BizException(HttpStatusEnum status, ErrorCodeEnum code, String message) {
        super(message);
        this.code = code.getCode();
        this.msg = message;
        this.status = status.code();
    }

    /**
     * 对外契约码 + i18n msgName，且保留 {@link HttpStatusEnum}（如 401）供需要读取 {@link #getStatus()} 的场景。
     */
    private BizException(HttpStatusEnum httpStatus, String apiCode, String message, String msgName,
            Object[] messageFormatArgs) {
        super(message);
        this.code = apiCode;
        this.msg = message;
        this.msgName = msgName;
        this.messageFormatArgs = messageFormatArgs == null ? null : messageFormatArgs.clone();
        this.status = httpStatus.code();
    }

    /**
     * 使用业务细分错误码（见 {@link BizExceptionCodeEnum}）构造异常；响应中的 code 为枚举自带编码。
     * 若对外必须固定为 4000/4011/4030 等，请使用 {@link #of(ErrorCodeEnum, IBizExceptionCodeEnum, Object...)}。
     */
    public static BizException of(IBizExceptionCodeEnum codeEnum, Object... params) {
        String message = codeEnum.formatMessage(params);
        return new BizException(codeEnum.getCode(), message, ((Enum<?>) codeEnum).name(), params);
    }

    /**
     * 使用对外统一错误码（须保留如 {@link ErrorCodeEnum#INVALID_PARAM 4000}、
     * {@link ErrorCodeEnum#UNAUTHORIZED_REDIRECT 4011}、{@link ErrorCodeEnum#PERMISSION_DENIED 4030} 等契约）
     * + 业务文案枚举构造异常；响应 code 取 {@code apiCode}，message 取枚举模板（便于 i18n）。
     */
    public static BizException of(ErrorCodeEnum apiCode, IBizExceptionCodeEnum messageEnum, Object... params) {
        String message = messageEnum.formatMessage(params);
        return new BizException(apiCode.getCode(), message, ((Enum<?>) messageEnum).name(), params);
    }

    /**
     * 同 {@link #of(ErrorCodeEnum, IBizExceptionCodeEnum, Object...)}，额外指定 HTTP 语义状态（如 {@link HttpStatusEnum#UNAUTHORIZED}），
     * 不改变对外 JSON 中的业务 {@code code}（仍取 {@code apiCode}）。
     */
    public static BizException of(HttpStatusEnum httpStatus, ErrorCodeEnum apiCode, IBizExceptionCodeEnum messageEnum,
            Object... params) {
        String message = messageEnum.formatMessage(params);
        return new BizException(httpStatus, apiCode.getCode(), message, ((Enum<?>) messageEnum).name(), params);
    }

    public String getCode() {
        return code;
    }

    /**
     * 业务枚举常量名（用于 i18n）；未通过 {@link #of} 系列工厂构造时可能为 null。
     */
    public String getMsgName() {
        return msgName;
    }

    /**
     * {@link #of(IBizExceptionCodeEnum, Object...)} 传入的占位参数副本；无则 null。
     */
    public Object[] getMessageFormatArgs() {
        return messageFormatArgs == null ? null : messageFormatArgs.clone();
    }

    public int getStatus() {
        return status;
    }
}
