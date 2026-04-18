package com.xspaceagi.system.application.validation;

import com.xspaceagi.system.application.constant.I18nLangTagConstraints;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 语言标识校验，委托 {@link com.xspaceagi.system.application.constant.I18nLangTagConstraints#isWellFormedLanguageTag(String)}（JDK Locale.Builder）。
 */
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
@Documented
@Constraint(validatedBy = ValidI18nLanguageTagValidator.class)
public @interface ValidI18nLanguageTag {

    String message() default I18nLangTagConstraints.LANG_TAG_MESSAGE_EN;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
