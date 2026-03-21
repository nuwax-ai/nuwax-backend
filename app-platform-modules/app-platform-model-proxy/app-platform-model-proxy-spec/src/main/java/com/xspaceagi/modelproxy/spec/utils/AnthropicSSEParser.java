package com.xspaceagi.modelproxy.spec.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AnthropicSSEParser {

    private static final Pattern INPUT_TOKENS_PATTERN = Pattern.compile("\"input_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern OUTPUT_TOKENS_PATTERN = Pattern.compile("\"output_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern TEXT_DELTA_PATTERN = Pattern.compile("\"type\"\\s*:\\s*\"text_delta\"");
    private static final Pattern TEXT_PATTERN = Pattern.compile("\"text\"\\s*:\\s*\"([^\"]*)\"");

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

                Matcher inputMatcher = INPUT_TOKENS_PATTERN.matcher(data);
                if (inputMatcher.find()) {
                    usage.inputTokens = Integer.parseInt(inputMatcher.group(1));
                }

                Matcher outputMatcher = OUTPUT_TOKENS_PATTERN.matcher(data);
                if (outputMatcher.find()) {
                    usage.outputTokens = Integer.parseInt(outputMatcher.group(1));
                }

                if (TEXT_DELTA_PATTERN.matcher(data).find()) {
                    usage.outputTokensDelta++;
                    Matcher textMatcher = TEXT_PATTERN.matcher(data);
                    if (textMatcher.find()) {
                        textBuilder.append(textMatcher.group(1));
                    }
                }
            }
        }

        usage.text = textBuilder.toString();

        if (JSON.isValidObject(sseData)) {
            JSONObject jsonObject = JSON.parseObject(sseData);
            JSONObject error = jsonObject.getJSONObject("error");
            if (error != null) {
                // 仅针对智谱模型
                usage.message = error.getString("message");
                long timestamp = extractResetTimestamp(sseData);
                if (timestamp > 0) {
                    usage.stopTimeSeconds = timestamp - System.currentTimeMillis() / 1000;
                    usage.stopAccount = true;
                }
                if ("1302".equals(error.getString("code"))) {
                    usage.stopAccount = true;
                    usage.stopTimeSeconds = 60;
                }
            }
        }

        return usage;
    }

    public static class TokenUsage {
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

    /**
     * 从 Anthropic 错误响应中提取限额重置时间戳[智谱编程套餐]
     *
     * @param response 错误响应 JSON 字符串
     * @return 重置时间戳（秒），如果未找到则返回 -1
     */
    public static long extractResetTimestamp(String response) {
        if (response == null || response.isEmpty()) {
            return -1;
        }

        Matcher matcher = Pattern.compile("您的限额将在\\s+(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})")
                .matcher(response);

        if (matcher.find()) {
            return LocalDateTime.parse(matcher.group(1),
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(ZoneId.systemDefault())
                    .toEpochSecond();
        }

        return -1;
    }
}
