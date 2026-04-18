package com.xspaceagi.system.application.validation;

import com.xspaceagi.system.application.constant.I18nLangTagConstraints;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link ValidI18nLanguageTag} 的实现。
 */
public class ValidI18nLanguageTagValidator implements ConstraintValidator<ValidI18nLanguageTag, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return I18nLangTagConstraints.isWellFormedLanguageTag(value);
    }
}
