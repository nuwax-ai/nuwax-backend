package com.xspaceagi.interceptor;

import com.xspaceagi.pay.spec.exception.PayGatewayBizException;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.*;
import jakarta.validation.UnexpectedTypeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@ControllerAdvice
public class AppExceptionHandler {

    /**
     * 处理异常消息,如果是业务异常将通过json的格式返回，否则根据模式来处理异常的显示.
     */
    @ExceptionHandler
    @ResponseBody
    public ReqResult<?> processException(Exception ex) {
        ReqResult<?> responseData;
        if (ex instanceof PayGatewayBizException payGatewayBizException) {
            responseData = ReqResult.error(
                    payGatewayBizException.getCode(),
                    payGatewayBizException.getDisplayCode(),
                    payGatewayBizException.getMessage());
        } else if (ex instanceof BizException bizException) {
            String message = resolveBizExceptionMessage(bizException);
            responseData = ReqResult.error(bizException.getCode(), message);
        } else {

            if (ex instanceof SpacePermissionException) {
                log.warn("Exception[SpacePermissionException] ", ex);
                responseData = ReqResult.error(ErrorCodeEnum.PERMISSION_DENIED.getCode(),
                        ErrorCodeEnum.PERMISSION_DENIED.getMsg());
            } else if (ex instanceof ResourcePermissionException) {
                responseData = ReqResult.error(ErrorCodeEnum.RESOURCE_PERMISSION_DENIED.getCode(),
                        ErrorCodeEnum.RESOURCE_PERMISSION_DENIED.getMsg());
            } else if (ex instanceof HttpRequestMethodNotSupportedException) {
                log.warn("Exception[HttpRequestMethodNotSupportedException] ", ex);
                responseData = ReqResult.error(ErrorCodeEnum.METHOD_NOT_ALLOWED.getCode(), ex.getMessage());
            } else if (ex instanceof MissingServletRequestParameterException) {
                log.warn("Exception[MissingServletRequestParameterException] ", ex);
                responseData = ReqResult.error(ErrorCodeEnum.INVALID_PARAM.getCode(), ex.getMessage());
            } else if (ex instanceof HttpMessageNotReadableException) {
                log.warn("Exception[HttpMessageNotReadableException] ", ex);
                responseData = ReqResult.error(ErrorCodeEnum.INVALID_PARAM.getCode(), ex.getMessage());
            } else if (ex instanceof MethodArgumentNotValidException methodArgumentNotValidException) {
                log.warn("Exception[MethodArgumentNotValidException] ", ex);
                String msg = methodArgumentNotValidException.getBindingResult().getFieldError().getDefaultMessage();
                responseData = ReqResult.error(ErrorCodeEnum.INVALID_PARAM.getCode(), msg);
            } else if (ex instanceof UnexpectedTypeException) {
                log.warn("Exception[UnexpectedTypeException] ", ex);
                responseData = ReqResult.error(ErrorCodeEnum.INVALID_PARAM.getCode(), ex.getMessage());
            } else if (ex instanceof HttpMediaTypeNotSupportedException) {
                log.warn("Exception[HttpMediaTypeNotSupportedException] ", ex);
                responseData = ReqResult.error(ErrorCodeEnum.INVALID_PARAM.getCode(), ex.getMessage());
            } else if (ex instanceof KnowledgeException knowledgeException) {
                log.warn("Exception[KnowledgeException] ", ex);
                responseData = ReqResult.error(knowledgeException.getCode(), knowledgeException.getDisplayCode(),
                        ex.getMessage());
            } else if (ex instanceof SystemManagerException systemManagerException) {
                log.warn("Exception[SystemManagerException] ", ex);
                responseData = ReqResult.error(systemManagerException.getCode(),
                        systemManagerException.getDisplayCode(), ex.getMessage());
            } else if (ex instanceof ComposeException composeException) {
                responseData = ReqResult.error(composeException.getCode(), composeException.getDisplayCode(),
                        composeException.getMessage());
            } else if (ex instanceof EcoMarketException ecoMarketException) {
                responseData = ReqResult.error(ecoMarketException.getCode(), ecoMarketException.getDisplayCode(),
                        ecoMarketException.getMessage());
            } else if (ex instanceof IllegalArgumentException) {
                responseData = ReqResult.error(ErrorCodeEnum.INVALID_PARAM.getCode(), ex.getMessage());
            } else if (ex instanceof NoResourceFoundException) {
                responseData = ReqResult.error(ErrorCodeEnum.API_NOT_FOUND.getCode(), ex.getMessage());
            } else if (ex instanceof AsyncRequestTimeoutException) {
                log.warn("Async request timeout ", ex);
                responseData = ReqResult.error(ErrorCodeEnum.SYS_ERROR.getCode(), "请求超时");
            } else {
                log.error("System error ", ex);
                responseData = ReqResult.error(ErrorCodeEnum.SYS_ERROR.getCode(), "系统开小差啦，请稍后重试");
            }
        }
        return responseData;
    }

    /**
     * BizException：按 {@link RequestContext#getLang()} 选择文案；{@code zh-CN}（忽略大小写）用中文模板，否则用英文模板；
     * 使用 {@link BizException#getMessageFormatArgs()} 与 {@link BizExceptionCodeEnum} 模板重新 {@link String#format}。
     * 无 {@code msgName}、无上下文、无 {@code lang} 或枚举名无法解析时回退 {@link Throwable#getMessage()}。
     */
    private static String resolveBizExceptionMessage(BizException ex) {
        String fallback = ex.getMessage();
        String msgName = ex.getMsgName();
        if (msgName == null || msgName.isEmpty()) {
            return fallback;
        }
        RequestContext<?> ctx = RequestContext.get();
        if (ctx == null) {
            return fallback;
        }
        String lang = ctx.getLang();
        if (lang == null || lang.isBlank()) {
            return fallback;
        }
        try {
            BizExceptionCodeEnum codeEnum = BizExceptionCodeEnum.valueOf(msgName);
            boolean zhCn = "zh-CN".equalsIgnoreCase(lang.trim());
            String tpl = zhCn ? codeEnum.getMessageZh() : codeEnum.getMessageEn();
            Object[] args = ex.getMessageFormatArgs();
            if (args == null || args.length == 0) {
                return tpl;
            }
            return String.format(tpl, args);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}