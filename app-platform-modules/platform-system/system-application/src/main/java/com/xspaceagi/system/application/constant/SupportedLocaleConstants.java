package com.xspaceagi.system.application.constant;

import java.util.List;

/**
 * BCP 47 语言标识白名单（覆盖主要经济体与常见访客区域）
 */
public final class SupportedLocaleConstants {

    private SupportedLocaleConstants() {
    }

    /**
     * 默认可选语言/区域（约数十种），按 tag 字母序便于 diff。
     */
    public static final List<String> DEFAULT_LOCALE_TAG_WHITELIST = List.of(
            "ar-SA",
            "bg-BG",
            "bn-IN",
            "cs-CZ",
            "da-DK",
            "de-DE",
            "el-GR",
            "en-AU",
            "en-CA",
            "en-GB",
            "en-IN",
            "en-SG",
            "en-US",
            "es-419",
            "es-ES",
            "es-MX",
            "et-EE",
            "fa-IR",
            "fi-FI",
            "fil-PH",
            "fr-CA",
            "fr-FR",
            "he-IL",
            "hi-IN",
            "hr-HR",
            "hu-HU",
            "id-ID",
            "is-IS",
            "it-IT",
            "ja-JP",
            "ko-KR",
            "lt-LT",
            "lv-LV",
            "ms-MY",
            "nb-NO",
            "nl-NL",
            "pl-PL",
            "pt-BR",
            "pt-PT",
            "ro-RO",
            "ru-RU",
            "sk-SK",
            "sl-SI",
            "sv-SE",
            "sw-KE",
            "th-TH",
            "tr-TR",
            "uk-UA",
            "ur-PK",
            "vi-VN",
            "zh-CN",
            "zh-HK",
            "zh-TW"
    );
}
