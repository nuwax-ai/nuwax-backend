package com.xspaceagi.system.spec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface I18nField {

    String field() default "";

    boolean subObj() default false;

    boolean id() default false;

    boolean keyPrefix() default false;
}
