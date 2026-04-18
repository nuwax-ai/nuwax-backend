package com.xspaceagi.system.spec.utils;


import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.SystemManagerException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.apache.commons.lang3.StringUtils;

import java.util.Set;

/**
 * 校验
 *
 * @author soddy
 */
public class ValidateUtil {

    private static final Validator VALIDATOR;

    static {
        ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory();
        VALIDATOR = validatorFactory.getValidator();
    }

    /**
     * 校验,不会抛异常
     *
     * @param t      对象
     * @param groups 分组集合
     * @param <T>    泛型
     * @return 错误信息字符串, 英文逗号分割
     */
    public static <T> String validate(T t, Class<?>... groups) {
        if (t == null) {
            return "请求不能为空";
        }
        StringBuilder msg = new StringBuilder(128);
        Set<ConstraintViolation<T>> validate = VALIDATOR.validate(t, groups);
        validate.forEach(v -> {
            if (!StringUtils.isEmpty(v.getMessage())) {
                msg.append(v.getMessage()).append(",");
            }
        });

        return msg.toString();
    }

    /**
     * 校验,会抛RuntimeException异常
     *
     * @param t      对象
     * @param groups 分组集合
     * @param <T>    泛型
     */
    public static <T> void validateThrowIfException(T t, Class<?>... groups) {
        String msg = validate(t, groups);
        if (StringUtils.isNotBlank(msg)) {
            throw SystemManagerException.build(BizExceptionCodeEnum.validationFailedWithDetail, msg);
        }
    }

}
