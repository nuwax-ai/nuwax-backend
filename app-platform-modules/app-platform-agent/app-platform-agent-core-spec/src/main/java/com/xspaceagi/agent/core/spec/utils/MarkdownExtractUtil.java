package com.xspaceagi.agent.core.spec.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 字段提取工具类
 */
public class MarkdownExtractUtil {

    /**
     * 提取字段值
     * 只认 frontmatter，不扫正文
     */
    public static String extractFieldValue(String markdownContent, String fieldName) {
        if (markdownContent == null || markdownContent.isBlank() || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        String yamlFrontMatter = extractYamlFrontMatter(markdownContent);
        if (yamlFrontMatter == null) {
            return null;
        }
        String fieldValue = extractYamlField(yamlFrontMatter, fieldName);
        return fieldValue != null ? fieldValue.trim() : null;
    }

    /**
     * 提取字段值，frontmatter 找不到时走正文匹配
     */
    public static String extractFieldValueWithBodyFallback(String markdownContent, String fieldName) {
        String fieldValue = extractFieldValue(markdownContent, fieldName);
        if (fieldValue != null) {
            return fieldValue;
        }
        if (markdownContent == null || markdownContent.isBlank()) {
            return null;
        }
        String byPattern = extractFieldByPattern(markdownContent, fieldName);
        return byPattern != null && !byPattern.isBlank() ? byPattern.trim() : null;
    }

    /**
     * 提取 YAML front matter 部分（--- 之间的内容）
     */
    private static String extractYamlFrontMatter(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }

        String trimmed = content.trim();
        // Windows 编辑器保存的 UTF-8 可能带 BOM，剥掉后再认 ---
        if (trimmed.startsWith("﻿")) {
            trimmed = trimmed.substring(1);
        }
        if (!trimmed.startsWith("---")) {
            return null;
        }

        // 找到第一个 --- 之后的内容
        int firstDashIndex = trimmed.indexOf("---");
        if (firstDashIndex < 0) {
            return null;
        }

        // 找到第二个 ---
        int secondDashIndex = trimmed.indexOf("---", firstDashIndex + 3);
        if (secondDashIndex < 0) {
            return null;
        }

        // 提取两个 --- 之间的内容
        return trimmed.substring(firstDashIndex + 3, secondDashIndex).trim();
    }

    /**
     * 从 YAML 内容中提取指定字段的值。
     * 支持普通标量（含引号包裹）与块标量（>/|，含 chomping -/+ 与显式缩进数字后缀，
     * 如 {@code description: >}、{@code description: |-}）：块内容取后续缩进比 key 行更深的行，
     * {@code >} 折叠为空格（空行作段落分隔），{@code |} 保留换行。
     */
    private static String extractYamlField(String yamlContent, String fieldName) {
        if (yamlContent == null || yamlContent.isBlank() || fieldName == null || fieldName.isBlank()) {
            return null;
        }

        String[] lines = yamlContent.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            // 精确匹配字段名，确保字段名后面紧跟冒号（可能有空格）
            // 使用正则表达式避免误匹配，例如：name: 应该匹配，但 name2: 不应该匹配 name:
            String pattern = "^" + Pattern.quote(fieldName) + "\\s*:";
            Pattern compiledPattern = Pattern.compile(pattern);
            Matcher matcher = compiledPattern.matcher(line);
            if (matcher.find()) {
                // 提取冒号后面的值
                int colonIndex = line.indexOf(':');
                if (colonIndex >= 0) {
                    String value = line.substring(colonIndex + 1).trim();
                    // 块标量：值是 >/|（可带 chomping/显式缩进后缀），内容在后续更缩进的行
                    if (value.matches("^[>|][0-9]*[+-]?")) {
                        return parseBlockScalar(lines, i, value.startsWith(">"));
                    }
                    // 移除可能的引号
                    if ((value.startsWith("\"") && value.endsWith("\"")) ||
                            (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    // 返回值（可能为空字符串，表示字段存在但值为空）
                    return value;
                }
            }
            // 其他字段的块标量：跳过其内容行，避免内容里的伪 key（如 description: 出现在别的字段值里）被误匹配
            if (isBlockScalarHeader(line)) {
                i = findBlockScalarEnd(lines, i);
            }
        }
        return null;
    }

    /**
     * 解析块标量内容：从 headerIndex 的下一行开始收集缩进比 key 行更深的行；
     * 折叠（>）时行间以空格连接、块中空行作段落分隔（输出换行），字面（|）时按行保留换行。
     * 现实兼容：大量手写/AI 生成的 SKILL.md 块内容顶格书写（YAML 规范非法但普遍存在），
     * 顶格行不结束块，仅遇到下一个 key 行（key: 后跟空格或行尾，冒号后紧跟 // 等非空格不算）才结束。
     * 尾部空行不计入（chomping 简化为 strip）；首尾空白由调用方 trim。
     */
    private static String parseBlockScalar(String[] lines, int headerIndex, boolean folded) {
        int baseIndent = indentOf(lines[headerIndex]);
        List<String> contentLines = new ArrayList<>();
        for (int i = headerIndex + 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) {
                // 空行可能是块中段落分隔，也可能是块结束后与下一 key 之间的间隔：先占位，由后续行缩进决定
                contentLines.add(null);
                continue;
            }
            if (indentOf(line) <= baseIndent && isKeyLine(line)) {
                break;
            }
            contentLines.add(line.trim());
        }
        // 去掉尾部空行占位（块结束后、下一 key 前的空行不属于内容）
        while (!contentLines.isEmpty() && contentLines.get(contentLines.size() - 1) == null) {
            contentLines.remove(contentLines.size() - 1);
        }
        StringBuilder content = new StringBuilder();
        boolean first = true;
        int pendingBlank = 0;
        for (String text : contentLines) {
            if (text == null) {
                if (!first) {
                    pendingBlank++;
                }
                continue;
            }
            if (first) {
                first = false;
            } else if (pendingBlank > 0) {
                // 空行序列保留换行：折叠时空行的换行不被折叠成空格（N 个空行输出 N 个换行），
                // 字面时空行本身是空文本行（N 个空行输出 N+1 个换行）
                content.append('\n');
                if (!folded) {
                    content.append('\n');
                }
                for (int i = 1; i < pendingBlank; i++) {
                    content.append('\n');
                }
                pendingBlank = 0;
            } else {
                content.append(folded ? ' ' : '\n');
            }
            content.append(text);
        }
        return content.toString();
    }

    /** 行的前导空格数（缩进） */
    private static int indentOf(String line) {
        return line.length() - line.stripLeading().length();
    }

    /**
     * 是否为 frontmatter 的 key 行（如 {@code license: MIT}、{@code tags:}）。
     * 冒号后必须跟空格或行尾（YAML 规范），因此 {@code http://a.com} 这类内容行不会误判为 key。
     */
    private static boolean isKeyLine(String line) {
        String trimmed = line.trim();
        return trimmed.matches("^[A-Za-z_][A-Za-z0-9_-]*:(\\s.*|$)");
    }

    /** 是否为块标量 header 行（{@code key: >} / {@code key: |-} 等，值仅含指示符） */
    private static boolean isBlockScalarHeader(String trimmedLine) {
        return trimmedLine.matches("^[A-Za-z_][A-Za-z0-9_-]*:\\s*[>|][0-9]*[+-]?$");
    }

    /**
     * 返回块标量内容结束后下一行的 index（第一个不属于内容的行；到结尾返回 lines.length）。
     * 判界规则与 {@link #parseBlockScalar} 一致：空行不定界，顶格非 key 行仍属内容，key 行结束。
     * 调用方将返回值赋给循环变量后经 i++ 停在该结束行上继续正常处理。
     */
    private static int findBlockScalarEnd(String[] lines, int headerIndex) {
        int baseIndent = indentOf(lines[headerIndex]);
        for (int i = headerIndex + 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) {
                continue;
            }
            if (indentOf(line) <= baseIndent && isKeyLine(line)) {
                return i - 1;
            }
        }
        return lines.length - 1;
    }

    /**
     * 使用正则表达式从内容中提取字段值
     * 支持多种格式：fieldName: value, "fieldName": "value", fieldName = value。
     */
    private static String extractFieldByPattern(String content, String fieldName) {
        if (content == null || content.isBlank() || fieldName == null || fieldName.isBlank()) {
            return null;
        }

        // 尝试匹配 name: value 格式（YAML 风格）
        Pattern pattern1 = Pattern.compile(
                "(?i)" + fieldName + "\\s*:\\s*([^\\n\\r]+)", Pattern.MULTILINE);
        Matcher matcher1 = pattern1.matcher(content);
        if (matcher1.find()) {
            String value = matcher1.group(1).trim();
            // 移除引号
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            return value;
        }

        // 尝试匹配 "name": "value" 格式（JSON 风格）
        Pattern pattern2 = Pattern.compile(
                "(?i)\"" + fieldName + "\"\\s*:\\s*\"([^\"]+)\"", Pattern.MULTILINE);
        Matcher matcher2 = pattern2.matcher(content);
        if (matcher2.find()) {
            return matcher2.group(1);
        }

        // 尝试匹配 name = value 格式
        Pattern pattern3 = Pattern.compile(
                "(?i)" + fieldName + "\\s*=\\s*([^\\n\\r]+)", Pattern.MULTILINE);
        Matcher matcher3 = pattern3.matcher(content);
        if (matcher3.find()) {
            String value = matcher3.group(1).trim();
            // 移除引号
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            return value;
        }

        return null;
    }
}