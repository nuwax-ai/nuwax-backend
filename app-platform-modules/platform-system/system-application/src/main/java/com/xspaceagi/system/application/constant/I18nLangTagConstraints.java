package com.xspaceagi.system.application.constant;

import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Optional;

/**
 * 语言标识（lang）校验：使用 JDK {@link Locale.Builder#setLanguageTag(String)}（IETF BCP 47），
 */
public final class I18nLangTagConstraints {

    /** Bean Validation 默认提示（中文） */
    public static final String LANG_TAG_MESSAGE = "语言标识格式不正确，请使用合法的语言标签，如 zh-CN、en-US、zh-Hans-CN";

    /** 与 {@link #LANG_TAG_MESSAGE} 语义一致，用于 {@code IllegalArgumentException} 等直接透出 API 的场景 */
    public static final String LANG_TAG_MESSAGE_EN =
            "Invalid language tag. Use a valid BCP 47 tag such as zh-CN, en-US, or zh-Hans-CN.";

    private I18nLangTagConstraints() {
    }

    /**
     * 是否为 JDK 可解析的合法语言标签，且包含非空 language 子标签。
     */
    public static boolean isWellFormedLanguageTag(String raw) {
        if (raw == null) {
            return false;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return false;
        }
        try {
            Locale loc = new Locale.Builder().setLanguageTag(s).build();
            return !loc.getLanguage().isEmpty();
        } catch (IllformedLocaleException e) {
            return false;
        }
    }

    /**
     * 解析并规范为入库形式：{@link Locale#toLanguageTag()} 原样（JDK 典型形式，如 zh-CN、zh-Hans-CN），
     * 与 {@link Locale}、HTTP 常见写法一致。
     */
    public static Optional<String> tryNormalizeToStoredForm(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return Optional.empty();
        }
        try {
            Locale loc = new Locale.Builder().setLanguageTag(s).build();
            if (loc.getLanguage().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(loc.toLanguageTag());
        } catch (IllformedLocaleException e) {
            return Optional.empty();
        }
    }

    /**
     * 是否指向同一语言标签（忽略大小写/等价规范化）。
     */
    public static boolean sameLanguageTag(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        Optional<String> ca = tryNormalizeToStoredForm(a);
        Optional<String> cb = tryNormalizeToStoredForm(b);
        if (ca.isPresent() && cb.isPresent()) {
            return ca.get().equalsIgnoreCase(cb.get());
        }
        return a.trim().equalsIgnoreCase(b.trim());
    }
}
