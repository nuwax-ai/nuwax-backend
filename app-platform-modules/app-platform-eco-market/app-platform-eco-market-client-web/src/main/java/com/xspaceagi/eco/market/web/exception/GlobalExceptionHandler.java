package com.xspaceagi.eco.market.web.exception;

import com.xspaceagi.eco.market.spec.exception.AdminPermissionException;
import com.xspaceagi.system.spec.dto.ReqResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 * 用于处理权限相关异常
 * 
 * @author soddy
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理管理员权限异常
     * 
     * @param e 权限异常
     * @return 错误响应
     */
    @ExceptionHandler(AdminPermissionException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ReqResult<Void> handleAdminPermissionException(AdminPermissionException e) {
        log.warn("Admin permission verification failed", e);
        ReqResult<Void> responseData = ReqResult.error(e.getMessage());

        return responseData;
    }
}