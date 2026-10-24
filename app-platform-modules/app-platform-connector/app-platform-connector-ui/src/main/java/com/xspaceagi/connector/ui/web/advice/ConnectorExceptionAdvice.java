package com.xspaceagi.connector.ui.web.advice;

import com.xspaceagi.connector.spec.exception.ConnectorException;
import com.xspaceagi.system.spec.dto.ReqResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 连接器模块异常 → 统一响应映射（errorCode 对外契约，设计文档 §7）。
 * 仅处理 ConnectorException，不影响平台全局异常处理。
 * HIGHEST_PRECEDENCE 保证先于平台全局兜底 handler 命中 ConnectorException。
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ConnectorExceptionAdvice {

    @ExceptionHandler(ConnectorException.class)
    public ReqResult<Object> handleConnectorException(ConnectorException ex) {
        if (ex.getHttpStatus() >= 500) {
            log.error("[connector] {}: {}", ex.getCode(), ex.getMessage(), ex);
        } else {
            log.warn("[connector] {}: {}", ex.getCode(), ex.getMessage());
        }
        ReqResult<Object> result = ReqResult.create(ex.getCode(), ex.getCode(), ex.getMessage(), ex.getMeta());
        return result;
    }
}
