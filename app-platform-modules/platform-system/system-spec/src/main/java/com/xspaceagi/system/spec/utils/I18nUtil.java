package com.xspaceagi.system.spec.utils;

import com.xspaceagi.system.spec.annotation.I18n;
import com.xspaceagi.system.spec.annotation.I18nField;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.I18nSideEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

@Slf4j
public class I18nUtil {

    public static String systemMessage(Map<String, String> langMap, String key, String... values) {
        if (langMap == null || key == null) {
            return key;
        }
        // 构建 key
        String lookupKey = key.startsWith(I18nSideEnum.Backend.getSide()) ? key : I18nSideEnum.Backend.getSide() + "." + key;

        // 获取翻译内容，如果没有则返回原始 key
        String template = langMap.get(lookupKey);
        if (template == null || template.isEmpty()) {
            return key;
        }

        // 没有参数时直接返回模板
        if (values == null || values.length == 0) {
            return template;
        }

        // 格式化字符串，捕获可能的异常
        try {
            return String.format(template, values);
        } catch (Exception e) {
            // 格式化失败时返回原始模板，便于排查问题
            return template;
        }
    }

    public static String systemMessage(String key, String... values) {
        if (RequestContext.get() == null || RequestContext.get().getLangMap() == null) {
            return key;
        }
        return systemMessage(RequestContext.get().getLangMap(), key, values);
    }

    public static void replaceSystemMessage(Object obj) {
        replaceSystemMessage("", obj);
    }

    public static void replaceSystemMessage(String modulePrefix, Object obj) {
        if (RequestContext.get() == null || RequestContext.get().getLangMap() == null) {
            return;
        }
        replaceSystemMessage(modulePrefix, RequestContext.get().getLangMap(), obj);
    }

    public static void replaceSystemMessage(Map<String, String> langMap, Object obj) {
        replaceSystemMessage("", langMap, obj);
    }

    private static void replaceSystemMessage(String modulePrefix, Map<String, String> langMap, Object obj) {
        if (obj == null) {
            return;
        }
        try {
            if (obj instanceof List) {
                if (!((List<?>) obj).isEmpty()) {
                    for (int i = 0; i < ((List<?>) obj).size(); i++) {
                        replaceMessage(modulePrefix, langMap, ((List<?>) obj).get(i));
                    }
                }
            } else {
                replaceMessage(modulePrefix, langMap, obj);
            }
        } catch (Exception ex) {
            log.warn("i18nConvert error, obj={}", obj);
        }
    }

    private static void replaceMessage(String modulePrefix, Map<String, String> langMap, Object obj) {
        I18n i18n = obj.getClass().getAnnotation(com.xspaceagi.system.spec.annotation.I18n.class);
        if (i18n == null) {
            return;
        }
        String keyPrefix = null;
        Class<?> clazz = obj.getClass();
        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            String fieldName = field.getName();
            if ("serialVersionUID".equals(fieldName)) continue;
            I18nField i18nField = field.getAnnotation(I18nField.class);
            if (i18nField != null && i18nField.keyPrefix()) {
                String getter = "get" + fieldName.substring(0, 1).toUpperCase() +
                        (fieldName.length() > 1 ? fieldName.substring(1) : "");
                try {
                    Method method = clazz.getMethod(getter, new Class[]{});
                    method.setAccessible(true);
                    keyPrefix = method.invoke(obj, new Object[]{}).toString();
                    break;
                } catch (Exception e) {
                    log.warn("i18nConvert error, keyPrefix={}", keyPrefix);
                    return;
                }
            }
        }

        for (Field field : fields) {
            try {
                String fieldName = field.getName();
                if ("serialVersionUID".equals(fieldName)) continue;
                I18nField i18nField = field.getAnnotation(I18nField.class);
                if (i18nField != null && i18nField.subObj()) {
                    String getter = "get" + fieldName.substring(0, 1).toUpperCase() +
                            (fieldName.length() > 1 ? fieldName.substring(1) : "");
                    Method method = clazz.getMethod(getter, new Class[]{});
                    method.setAccessible(true);
                    Object subObj = method.invoke(obj, new Object[]{});
                    replaceSystemMessage(langMap, subObj);
                    continue;
                }

                if (!field.getGenericType().toString().equals("class java.lang.String")) {
                    continue;
                }

                String fieldKey = fieldName;
                if (i18nField != null && !i18nField.id() && StringUtils.isNotBlank(i18nField.field())) {
                    fieldKey = i18nField.field();
                }

                String setter = "set" + fieldName.substring(0, 1).toUpperCase() + (fieldName.length() > 1 ? fieldName.substring(1) : "");
                Method method = clazz.getMethod(setter, String.class);
                method.setAccessible(true);
                String key;
                if (keyPrefix == null) {
                    key = I18nSideEnum.Backend.getSide() + "." + modulePrefix + i18n.module() + "." + fieldKey;
                } else {
                    key = I18nSideEnum.Backend.getSide() + "." + modulePrefix + i18n.module() + "." + keyPrefix + "." + fieldKey;
                }
                String content = langMap.get(key);
                if (StringUtils.isNotBlank(content)) {
                    method.invoke(obj, content);
                }
            } catch (Exception e) {
                log.warn("convert error, obj={}", obj);
            }
        }
    }
}
