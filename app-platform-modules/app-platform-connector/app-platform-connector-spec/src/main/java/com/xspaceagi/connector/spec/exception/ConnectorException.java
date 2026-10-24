package com.xspaceagi.connector.spec.exception;

import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import lombok.Getter;

import java.util.Map;

/**
 * 连接器模块业务异常。
 * errorCode 对外契约见 {@link ConnectorErrorCode}；meta 用于携带附加信息
 * （如 connection_required 时的授权入口、upstream_error 时的上游状态码）。
 */
@Getter
public class ConnectorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ConnectorErrorCode errorCode;

    private final Map<String, Object> meta;

    public ConnectorException(ConnectorErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null, null);
    }

    public ConnectorException(ConnectorErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public ConnectorException(ConnectorErrorCode errorCode, Map<String, Object> meta) {
        this(errorCode, errorCode.getMessage(), meta, null);
    }

    public ConnectorException(ConnectorErrorCode errorCode, String message, Map<String, Object> meta) {
        this(errorCode, message, meta, null);
    }

    public ConnectorException(ConnectorErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, null, cause);
    }

    public ConnectorException(ConnectorErrorCode errorCode, String message, Map<String, Object> meta, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.meta = meta;
    }

    public String getCode() {
        return errorCode.getCode();
    }

    public int getHttpStatus() {
        return errorCode.getHttpStatus();
    }
}
