package com.xspaceagi.system.application.service;

import java.util.Map;

/**
 * 使用租户默认对话模型将翻译
 */
public interface I18nLlmTranslator {

    String translate(String text, String sourceLangTag, String targetLangTag);

    Map<String, String> translateBatch(Map<String, String> textByKey, String sourceLangTag, String targetLangTag);
}
