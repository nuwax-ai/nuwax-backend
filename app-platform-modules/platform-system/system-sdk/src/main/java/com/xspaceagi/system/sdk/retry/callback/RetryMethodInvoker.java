package com.xspaceagi.system.sdk.retry.callback;

import com.xspaceagi.system.sdk.retry.constant.CsConstants;
import com.xspaceagi.system.sdk.retry.context.RetryContext;
import com.xspaceagi.system.sdk.retry.dto.RetryExecDto;
import com.xspaceagi.system.sdk.retry.exception.RetryException;
import com.xspaceagi.system.sdk.retry.utils.ExceptionUtils;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

@Slf4j
@Service
public class RetryMethodInvoker implements ApplicationContextAware {

    @Setter
    private ApplicationContext applicationContext;

    /**
     * 重试方法回调
     */
    public Object methodInvoke(RetryExecDto dto) {
        try {
            String beanName = dto.getBeanName();
            Object bean;
            if (beanName.contains(CsConstants.DOT)) {
                bean = getBeanByType(Class.forName(beanName));
            } else {
                bean = getBeanByName(beanName);
            }
            if (Objects.isNull(bean)) {
                String msg = "重试调用-获取bean失败";
                log.error(msg);
                throw new RetryException(msg);
            }
            //设置来源
            RetryContext retryContext = RetryContext.get();
            retryContext.setFormRetry(true);

            return doInvoke(dto, bean);
        } catch (InvocationTargetException e) {
            log.error("Retry invocation error", e);
            Throwable targetException = e.getTargetException();
            if (targetException instanceof RetryException) {
                targetException = targetException.getCause();
            }
            throw new RetryException(ExceptionUtils.getStackTrace(targetException));
        } catch (Throwable e) {
            log.error("Retry invocation error", e);
            throw new RetryException(ExceptionUtils.getStackTrace(e));
        }
    }

    private Object doInvoke(RetryExecDto dto, Object bean) throws Exception {
        //find method
        String methodName = dto.getMethodName();
        String[] argClassNames = dto.getArgClassNames();
        Class[] clsArr = new Class[argClassNames.length];
        for (int i = 0; i < argClassNames.length; i++) {
            clsArr[i] = Class.forName(argClassNames[i]);
        }
        Method method = bean.getClass().getDeclaredMethod(methodName, clsArr);
        String argStr = dto.getArgStr();
        if (StringUtils.isBlank(argStr)) {
            return method.invoke(bean);
        } else {
            Object[] objArr = (Object[]) JsonSerializeUtil.parseObjectGeneric(argStr);
            return method.invoke(bean, objArr);
        }
    }

    private <T> T getBeanByName(String name) {
        return (T) applicationContext.getBean(name);
    }

    private <T> T getBeanByType(Class<T> t) {
        return applicationContext.getBean(t);
    }
}
