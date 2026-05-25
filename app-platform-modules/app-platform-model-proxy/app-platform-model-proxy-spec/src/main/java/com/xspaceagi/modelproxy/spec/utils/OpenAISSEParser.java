package com.xspaceagi.modelproxy.spec.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OpenAISSEParser {
    private static final Pattern CAHED_TOKENS_PATTERN = Pattern.compile("\"cached_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern PROMPT_TOKENS_PATTERN = Pattern.compile("\"prompt_tokens\"\\s*:\\s*(\\d+)");

    private static final Pattern CACHE_PROMPT_CREATE_TOKENS_PATTERN = Pattern.compile("\"cache_creation_input_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern COMPLETION_TOKENS_PATTERN = Pattern.compile("\"completion_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern TOTAL_TOKENS_PATTERN = Pattern.compile("\"total_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern CONTENT_PATTERN = Pattern.compile("\"content\"\\s*:\\s*\"([^\"]*)\"");

    public static TokenUsage extractTokenUsage(String sseData) {
        TokenUsage usage = new TokenUsage();
        StringBuilder textBuilder = new StringBuilder();

        String[] lines = sseData.split("\n");

        for (String line : lines) {
            line = line.trim();

            if (line.startsWith("data:")) {
                String data = line.substring(5).trim();

                if (data.equals("[DONE]")) {
                    continue;
                }

                Matcher cachedMatcher = CAHED_TOKENS_PATTERN.matcher(data);
                if (cachedMatcher.find()) {
                    usage.cachedTokens = Integer.parseInt(cachedMatcher.group(1));
                }

                Matcher promptMatcher = PROMPT_TOKENS_PATTERN.matcher(data);
                if (promptMatcher.find()) {
                    usage.promptTokens = Integer.parseInt(promptMatcher.group(1));
                }

                Matcher promptCacheCreateMatcher = CACHE_PROMPT_CREATE_TOKENS_PATTERN.matcher(data);
                if (promptCacheCreateMatcher.find()) {
                    usage.promptTokens += Integer.parseInt(promptCacheCreateMatcher.group(1));
                }

                Matcher completionMatcher = COMPLETION_TOKENS_PATTERN.matcher(data);
                if (completionMatcher.find()) {
                    usage.completionTokens = Integer.parseInt(completionMatcher.group(1));
                }

                Matcher totalMatcher = TOTAL_TOKENS_PATTERN.matcher(data);
                if (totalMatcher.find()) {
                    usage.totalTokens = Integer.parseInt(totalMatcher.group(1));
                }

                Matcher contentMatcher = CONTENT_PATTERN.matcher(data);
                while (contentMatcher.find()) {
                    String content = contentMatcher.group(1);
                    textBuilder.append(content);
                    usage.contentDeltaCount++;
                }
            }
        }

        usage.content = textBuilder.toString();
        return usage;
    }

    public static class TokenUsage {
        public long cachedTokens = 0;
        public long promptTokens = 0;
        public long completionTokens = 0;
        public long totalTokens = 0;
        public int contentDeltaCount = 0;
        public String content = "";

        @Override
        public String toString() {
            return String.format("Prompt Tokens: %d, Completion Tokens: %d, Total Tokens: %d, Content Delta Count: %d, Content: %s",
                    promptTokens, completionTokens, totalTokens, contentDeltaCount, content);
        }
    }
}
