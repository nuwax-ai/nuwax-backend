package com.xspaceagi.connector.ui.web.advice;

import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.system.spec.dto.ReqResult;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 连接器模块入参校验异常 → 统一信封（errorCode=validation_error，HTTP 200 + 信封）。
 * <p>
 * 校验类异常（@Valid 失败、@RequestParam 缺失、请求体 JSON 非法）若不在此拦下，
 * 会一路冒泡到平台兜底 handler 变成「系统异常」，调用方看不出是哪个字段超长/缺失。
 * 这里把字段级原因原样带出，meta.fields 供前端逐字段标红。
 * <p>
 * 作用域限定在连接器 controller 包，不改变其他模块的既有异常语义。
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.xspaceagi.connector.ui.web.controller")
public class ConnectorValidationAdvice {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ReqResult<Object> handleBodyValidation(MethodArgumentNotValidException ex) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.add(field(error.getField(), error.getDefaultMessage()));
        }
        return validationError(join(fields), fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ReqResult<Object> handleParamValidation(ConstraintViolationException ex) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            fields.add(field(String.valueOf(violation.getPropertyPath()), violation.getMessage()));
        }
        return validationError(join(fields), fields);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ReqResult<Object> handleMissingParam(MissingServletRequestParameterException ex) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field(ex.getParameterName(), "必填参数缺失"));
        return validationError(join(fields), fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ReqResult<Object> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("[connector] 请求体解析失败: {}", ex.getMessage());
        return validationError("请求体不是合法 JSON 或字段类型不匹配", List.of());
    }

    // ---------- 内部 ----------

    private ReqResult<Object> validationError(String message, List<Map<String, Object>> fields) {
        log.warn("[connector] {}: {}", ConnectorErrorCode.VALIDATION_ERROR.getCode(), message);
        String code = ConnectorErrorCode.VALIDATION_ERROR.getCode();
        return ReqResult.create(code, code, message, fields.isEmpty() ? null : Map.of("fields", fields));
    }

    private Map<String, Object> field(String name, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("field", name);
        item.put("message", message);
        return item;
    }

    /** 拼成「displayName 长度不能超过 128；name 必填」这类一眼可见的提示。 */
    private String join(List<Map<String, Object>> fields) {
        if (fields.isEmpty()) {
            return "请求参数校验失败";
        }
        return fields.stream()
                .map(this::label)
                .collect(Collectors.joining("；"));
    }

    /**
     * 单条提示；注解消息里若已写了字段名（历史写法「xxx 必填」）就不再重复前缀，
     * 免得出现「displayName displayName 必填」。
     */
    private String label(Map<String, Object> item) {
        String name = String.valueOf(item.get("field"));
        String message = String.valueOf(item.get("message"));
        return message.startsWith(name) ? message : name + " " + message;
    }
}
