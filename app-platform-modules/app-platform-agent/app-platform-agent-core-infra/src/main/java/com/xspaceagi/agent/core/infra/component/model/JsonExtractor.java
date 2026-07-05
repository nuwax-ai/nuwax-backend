package com.xspaceagi.agent.core.infra.component.model;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 从混合文本中精确提取 JSON 对象/数组。
 * 使用 Jackson 流式解析器校验边界，比手工状态机更健壮。
 */
public class JsonExtractor {

    private static final JsonFactory FACTORY = new JsonFactory();

    public static List<String> extract(String text) {
        List<String> results = new ArrayList<>();
        if (text == null || text.isEmpty()) return results;

        // 跳过属性名中的 { [ (如 "prompt":"{\"x\":1}" 这种不用提取)
        boolean inTopString = false;

        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);

            // 跟踪是否在顶层字符串值中
            if (c == '"') {
                inTopString = !inTopString;
                i++;
                continue;
            }

            if (inTopString) {
                i++;
                continue;
            }

            if (c == '{' || c == '[') {
                int end = tryParseJson(text, i);
                if (end > i) {
                    results.add(text.substring(i, end + 1));
                    i = end + 1;
                    continue;
                }
            }
            i++;
        }
        return results;
    }

    public static String extractLast(String text) {
        List<String> list = extract(text);
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    /**
     * 从 start 位置尝试用 Jackson 解析一段完整 JSON。
     * 成功返回结束下标（含），失败返回 -1。
     */
    private static int tryParseJson(String text, int start) {
        try (JsonParser parser = FACTORY.createParser(text.substring(start))) {
            JsonToken token = parser.nextToken();
            if (token == null) return -1;

            // 递归跳过整个 token（对象/数组会自动跳过所有子元素）
            parser.skipChildren();

            // parser.currentLocation() 给出的是解码后的字符偏移，
            // token 结束位置 = start + 当前字节偏移 → 转为 char 偏移
            long byteOffset = parser.currentLocation().getCharOffset();
            int end = start + (int) byteOffset - 1;
            if (end <= start) return -1;

            // 简单校验：结束字符必须是 } 或 ]
            char lastChar = text.charAt(end);
            return (lastChar == '}' || lastChar == ']') ? end : -1;

        } catch (IOException e) {
            return -1;
        }
    }
}
