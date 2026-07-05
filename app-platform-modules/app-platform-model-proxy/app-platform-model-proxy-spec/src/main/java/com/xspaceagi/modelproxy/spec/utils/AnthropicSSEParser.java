package com.xspaceagi.modelproxy.spec.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AnthropicSSEParser {

    //cache_read_input_tokens
    private static final Pattern CACHE_INPUT_TOKENS_PATTERN = Pattern.compile("\"cache_read_input_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern INPUT_TOKENS_PATTERN = Pattern.compile("\"input_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern CACHE_PROMPT_CREATE_TOKENS_PATTERN = Pattern.compile("\"cache_creation_input_tokens\"\\s*:\\s*(\\d+)");

    private static final Pattern OUTPUT_TOKENS_PATTERN = Pattern.compile("\"output_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern TEXT_DELTA_PATTERN = Pattern.compile("\"type\"\\s*:\\s*\"text_delta\"");
    private static final Pattern TEXT_PATTERN = Pattern.compile("\"text\"\\s*:\\s*\"([^\"]*)\"");

    public static TokenUsage extractTokenUsage(String data) {
        TokenUsage usage = new TokenUsage();
        StringBuilder textBuilder = new StringBuilder();
        Matcher caheInputMatcher = CACHE_INPUT_TOKENS_PATTERN.matcher(data);
        while (caheInputMatcher.find()) {
            usage.cacheInputTokens = Integer.parseInt(caheInputMatcher.group(1));
        }

        Matcher inputMatcher = INPUT_TOKENS_PATTERN.matcher(data);
        while (inputMatcher.find()) {
            // 取最后一个为计费数量
            usage.inputTokens = Integer.parseInt(inputMatcher.group(1));
        }

        Matcher promptCacheCreateMatcher = CACHE_PROMPT_CREATE_TOKENS_PATTERN.matcher(data);
        while (promptCacheCreateMatcher.find()) {
            usage.cacheCreationInputTokens = Integer.parseInt(promptCacheCreateMatcher.group(1));
        }

        Matcher outputMatcher = OUTPUT_TOKENS_PATTERN.matcher(data);
        while (outputMatcher.find()) {
            usage.outputTokens = Integer.parseInt(outputMatcher.group(1));
        }

        if (TEXT_DELTA_PATTERN.matcher(data).find()) {
            usage.outputTokensDelta++;
            Matcher textMatcher = TEXT_PATTERN.matcher(data);
            while (textMatcher.find()) {
                textBuilder.append(textMatcher.group(1));
            }
        }
        usage.text = textBuilder.toString();
        if (usage.outputTokens == 0L) {
            usage.inputTokens = 0L;// 避免统计token时算价
        }
        return usage;
    }

    public static class TokenUsage {
        public long cacheCreationInputTokens = 0;
        public long cacheInputTokens = 0;
        public long inputTokens = 0;
        public long outputTokens = 0;
        public int outputTokensDelta = 0;
        public String text = "";
        public String message;
        public long stopTimeSeconds = 0;
        public boolean stopAccount = false;

        @Override
        public String toString() {
            return String.format("Input Tokens: %d, Output Tokens: %d, Text Delta Count: %d, Text: %s",
                    inputTokens, outputTokens, outputTokensDelta, text);
        }
    }
}
