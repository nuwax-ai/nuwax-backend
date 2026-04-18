package com.xspaceagi.system.domain.track.reporter;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.system.sdk.operate.OperateTypeEnum;
import com.xspaceagi.system.sdk.operate.OperationLogReporter;
import com.xspaceagi.system.sdk.server.ITrackerReportService;
import com.xspaceagi.system.spec.common.LoginWrapper;
import com.xspaceagi.system.spec.common.OperatorLogContext;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.*;

/**
 * 日志上报切面
 */
@Slf4j
@Aspect
@Component
public class OperateLogAspect {
    @Autowired
    private LoginWrapper loginWrapper;

    @Resource
    private ITrackerReportService trackerReportService;

    /**
     * SpEL 表达式解析器
     */
    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * 参数名发现器
     */
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    @AfterReturning(returning = "res", pointcut = "@annotation(operationLogReporter)")
    public void afterReturn(JoinPoint joinPoint, Object res,
                            OperationLogReporter operationLogReporter) {
        try {
            //获取用户Id

            var userContext = Optional.ofNullable(RequestContext.get())
                    .map(RequestContext::getUserContext)
                    .orElseGet(() ->
                            UserContext.builder()
                                    .userId(0L)
                                    .userName("无法获取")
                                    .build()
                    );
            Long userId = userContext.getUserId();
            String userName = userContext.getUserName();

            //获取IP
            String remoteAddr = "";
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                remoteAddr = request.getRemoteAddr();
            }
            // 获取请求参数
            Map<String, String> params = new HashMap<>();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                Enumeration<String> parameterNames = request.getParameterNames();
                while (parameterNames.hasMoreElements()) {
                    String paramName = parameterNames.nextElement();
                    String paramValue = request.getParameter(paramName);
                    params.put(paramName, paramValue);
                }
            }

            // 构建额外数据对象（同时包含 HTTP 参数和 SpEL 提取的参数）
            String extraContent = "";
            try {
                OperateLogExtraData.OperateLogExtraDataBuilder extraDataBuilder = OperateLogExtraData.builder()
                        .httpParams(params);

                // 如果配置了 SpEL 表达式，提取 SpEL 参数
                if (StringUtils.hasText(operationLogReporter.spelExpression())) {
                    Object spelData = extractSpelData(joinPoint, operationLogReporter.spelExpression());
                    extraDataBuilder.spelData(spelData)
                            .spelExpression(operationLogReporter.spelExpression());
                }

                extraContent = JSON.toJSONString(extraDataBuilder.build());
            } catch (Exception e) {
                log.error("参数转换异常", e);
            }

            var resp = (ReqResult<?>) res;
            String uuid = UUID.randomUUID().toString();
            log.debug("uuid:{}", uuid);
            //1:操作类型;2:访问日志; 查询操作,默认都是访问日志
            var actionType = operationLogReporter.actionType();
            log.debug("actionType:{}", actionType.getName());
            var operateType = switch (actionType) {
                case QUERY -> OperateTypeEnum.ACCESS.getType();
                default -> OperateTypeEnum.OPERATE.getType();
            };


            OperatorLogContext vo = OperatorLogContext.builder()
                    .clientTime(System.currentTimeMillis())
                    .operateType(operateType)
                    .userId(userId)
                    .creator(userName)
                    .orgId(userContext.getOrgId())
                    .orgName(userContext.getOrgName())
                    .systemCode(operationLogReporter.systemCode().toString())
                    .systemName(operationLogReporter.systemCode().getDesc())
                    .object(operationLogReporter.objectName())
                    .ip(remoteAddr)
                    .action(operationLogReporter.actionType().getName())
                    .operateContent(operationLogReporter.action())
                    .extraContent(extraContent)
                    .tenantId(userContext.getTenantId())
                    .build();


            //文件下载流 ,resp 是void,为空
            if (Objects.isNull(resp) || "0000".equals(resp.getCode())) {
                vo.setStatus(LogConstant.STATUS_success);
            } else {
                vo.setStatus(LogConstant.STATUS_FAIL);
            }

            Optional<OperationLogContext> operationLogContextOptional = OperationLogContextHandler.get();
            if (operationLogContextOptional.isPresent()) {
                OperationLogContext operationLogContext = operationLogContextOptional.get();
                if (Objects.nonNull(operationLogContext.getOrgId())) {
                    vo.setOrgId(operationLogContext.getOrgId());
                }

            }

            this.trackerReportService.reportLog(vo);

            OperationLogContextHandler.remove();
        } catch (Exception e) {
            log.error("Log report error: ", e);
        }
    }


    @AfterThrowing(throwing = "ex", pointcut = "@annotation(operationLogReporter)")
    public void afterThrow(JoinPoint joinPoint, Throwable ex, OperationLogReporter operationLogReporter) {
        try {
            //获取用户Id
            var userContext = Optional.ofNullable(RequestContext.get())
                    .map(RequestContext::getUserContext)
                    .orElseGet(() ->
                            UserContext.builder()
                                    .userId(0L)
                                    .userName("无法获取")
                                    .build()
                    );
            Long userId = userContext.getUserId();
            String userName = userContext.getUserName();
            
            //获取IP
            String remoteAddr = "";
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                remoteAddr = request.getRemoteAddr();
            }

            String uuid = UUID.randomUUID().toString();
            log.info("uuid:{}", uuid);

            //1:操作类型;2:访问日志; 查询操作,默认都是访问日志
            var actionType = operationLogReporter.actionType();
            log.debug("actionType:{}", actionType.getName());
            var operateType = switch (actionType) {
                case QUERY -> OperateTypeEnum.ACCESS.getType();
                default -> OperateTypeEnum.OPERATE.getType();
            };
            OperatorLogContext vo = OperatorLogContext.builder()
                    .clientTime(System.currentTimeMillis())
                    .operateType(operateType)
                    .userId(userId)
                    .creator(userName)
                    .orgId(userContext.getOrgId())
                    .orgName(userContext.getOrgName())
                    .systemCode(operationLogReporter.systemCode().toString())
                    .systemName(operationLogReporter.systemCode().getDesc())
                    .object(operationLogReporter.objectName())
                    .ip(remoteAddr)
                    .action(operationLogReporter.actionType().getName())
                    .operateContent(operationLogReporter.action())
                    .extraContent(operationLogReporter.objectName())
                    .tenantId(userContext.getTenantId())
                    .build();


            this.trackerReportService.reportLog(vo);

            OperationLogContextHandler.remove();
        } catch (Exception e) {
            log.error("Log report error: ", e);
        }
    }

    /**
     * 使用 SpEL 表达式提取方法参数中的特定数据
     *
     * @param joinPoint      切点
     * @param spelExpression SpEL 表达式
     * @return 提取的数据对象（非 JSON 字符串）
     */
    private Object extractSpelData(JoinPoint joinPoint, String spelExpression) {
        try {
            // 获取方法参数名
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            String[] parameterNames = parameterNameDiscoverer.getParameterNames(signature.getMethod());
            if (parameterNames == null) {
                log.warn("无法获取方法参数名，SpEL 表达式无法执行");
                return null;
            }

            // 构建 SpEL 上下文
            EvaluationContext context = new StandardEvaluationContext();
            Object[] args = joinPoint.getArgs();
            for (int i = 0; i < parameterNames.length; i++) {
                context.setVariable(parameterNames[i], args[i]);
            }

            // 执行 SpEL 表达式并返回原始对象
            return parser.parseExpression(spelExpression).getValue(context);
        } catch (Exception e) {
            log.error("SpEL 表达式解析失败: {}", spelExpression, e);
            return null;
        }
    }

}