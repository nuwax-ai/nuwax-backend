package com.xspaceagi.system.web.i18n;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.system.application.service.I18nLlmTranslator;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通过租户默认对话模型翻译（{@link ModelApplicationService#queryDefaultModelConfig()}）
 */
@Slf4j
@Component
public class I18nLlmTranslatorImpl implements I18nLlmTranslator {

    private static final int MAX_CHUNK = 6000;

    @Resource
    private ModelApplicationService modelApplicationService;

    @Override
    public String translate(String text, String sourceLangTag, String targetLangTag) {
        if (StringUtils.isBlank(text)) {
            return text;
        }
        if (sourceLangTag != null && targetLangTag != null && sourceLangTag.equalsIgnoreCase(targetLangTag)) {
            return text;
        }
        StringBuilder merged = new StringBuilder();
        for (int start = 0; start < text.length(); start += MAX_CHUNK) {
            int end = Math.min(start + MAX_CHUNK, text.length());
            if (end < text.length()) {
                int lineBreak = text.lastIndexOf('\n', end - 1);
                if (lineBreak > start) {
                    end = lineBreak + 1;
                }
            }
            String chunk = text.substring(start, end);
            merged.append(translateChunk(chunk, sourceLangTag, targetLangTag));
        }
        return merged.toString();
    }

    @Override
    public Map<String, String> translateBatch(Map<String, String> textByKey, String sourceLangTag, String targetLangTag) {
        if (textByKey == null || textByKey.isEmpty()) {
            return Map.of();
        }
        try {
            String prompt = buildBatchPrompt(textByKey, sourceLangTag, targetLangTag);
            String raw = modelApplicationService.call(prompt);
            String cleaned = sanitizeModelOutput(raw);
            String jsonText = extractJsonObject(cleaned);
            if (!JSON.isValidObject(jsonText)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemI18nBatchTranslationNotJson,
                        StringUtils.abbreviate(cleaned, 200));
            }
            JSONObject jsonObject = JSON.parseObject(jsonText);
            Map<String, String> result = new LinkedHashMap<>();
            textByKey.keySet().forEach(key -> {
                String v = jsonObject.getString(key);
                if (StringUtils.isBlank(v)) {
                    String sourceText = textByKey.get(key);
                    if (StringUtils.isBlank(sourceText)) {
                        throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemI18nBatchTranslationKeyOrValueMissing, key);
                    }
                    log.warn("批量翻译缺少 key 或值为空，回退单条翻译: {}", key);
                    String fallback = translate(sourceText, sourceLangTag, targetLangTag);
                    if (StringUtils.isBlank(fallback)) {
                        throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemI18nBatchTranslationKeyOrValueMissing, key);
                    }
                    v = fallback;
                }
                result.put(key, v);
            });
            return result;
        } catch (Exception e) {
            log.warn("大模型批量翻译失败 {} -> {}", sourceLangTag, targetLangTag, e);
            throw toBizException(e);
        }
    }

    private String translateChunk(String chunk, String sourceLangTag, String targetLangTag) {
        try {
            String prompt = buildPrompt(chunk, sourceLangTag, targetLangTag);
            String raw = modelApplicationService.call(prompt);
            String cleaned = sanitizeModelOutput(raw);
            if (StringUtils.isBlank(cleaned)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemI18nTranslationEmpty);
            }
            return cleaned;
        } catch (Exception e) {
            log.warn("大模型翻译失败 {} -> {}", sourceLangTag, targetLangTag, e);
            throw toBizException(e);
        }
    }

    private BizException toBizException(Exception e) {
        if (e instanceof BizException bizException) {
            return bizException;
        }
        if (e instanceof NonTransientAiException && containsAuthFailure(e)) {
            return BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemI18nTranslationModelAuthFailed);
        }
        return BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemI18nTranslationFailedWithReason,
                e.getMessage() != null ? e.getMessage() : "");
    }

    private boolean containsAuthFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String msg = current.getMessage();
            if (StringUtils.containsIgnoreCase(msg, "401")
                    || StringUtils.contains(msg, "身份验证失败")
                    || StringUtils.containsIgnoreCase(msg, "authentication")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String buildPrompt(String sourceText, String sourceLangTag, String targetLangTag) {
        return """
                You are a professional UI localization translator.

                Translate the text below from language "%s" to language "%s".

                Rules:
                - Output ONLY the translated text. No quotes, no markdown fences, no explanations.
                - Preserve placeholders and formatting exactly: %%s, %%d, {0}, {{name}}, HTML tags, etc.
                - Preserve line breaks and meaningful whitespace.

                Text to translate:
                """.formatted(sourceLangTag, targetLangTag)
                + sourceText;
    }

    private static String buildBatchPrompt(Map<String, String> textByKey, String sourceLangTag, String targetLangTag) {
        String json = JSON.toJSONString(textByKey);
        return """
                You are a professional UI localization translator.

                Translate all values in the given JSON object from language "%s" to language "%s".
                Keep the keys exactly unchanged.

                Rules:
                - Output ONLY one JSON object.
                - Keep the same keys, no added/removed keys.
                - Translate only values.
                - Preserve placeholders and formatting exactly: %%s, %%d, {0}, {{name}}, HTML tags, etc.

                Input JSON:
                %s
                """.formatted(sourceLangTag, targetLangTag, json);
    }

    private static String extractJsonObject(String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private static String sanitizeModelOutput(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) {
                t = t.substring(firstNl + 1);
            }
            int fence = t.lastIndexOf("```");
            if (fence > 0) {
                t = t.substring(0, fence);
            }
        }
        return t.trim();
    }
}
