package com.xspaceagi.agent.core.infra.speech.tts;

import java.util.regex.Pattern;

/**
 * 将 Agent 返回的 Markdown / HTML 富文本转为适合 TTS 朗读与设备展示的纯文本。
 */
public final class TtsTextSanitizer {

    private static final Pattern MARKDOWN_CUSTOM_PROCESS_BLOCK = Pattern.compile(
            "<markdown-custom-process\\b[^>]*>.*?</markdown-custom-process\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MARKDOWN_CUSTOM_PROCESS_SELF = Pattern.compile(
            "<markdown-custom-process\\b[^>]*/\\s*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PLUGIN_WRAPPER_DIV = Pattern.compile(
            "<div\\b[^>]*>\\s*<markdown-custom-process\\b.*?</markdown-custom-process\\s*>\\s*</div\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SCRIPT_STYLE_BLOCK = Pattern.compile(
            "<(script|style)\\b[^>]*>.*?</\\1\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKDOWN_TABLE_LINE = Pattern.compile("(?m)^\\s*\\|.*\\|\\s*$");
    private static final Pattern MARKDOWN_TABLE_SEPARATOR = Pattern.compile("(?m)^\\s*\\|?[\\s:-]+\\|[\\s|:-]*$");
    private static final Pattern MARKDOWN_BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern MARKDOWN_ITALIC = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)");
    private static final Pattern MARKDOWN_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]+\\)");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("(?m)^#{1,6}\\s+");
    private static final Pattern MARKDOWN_BULLET_LIST = Pattern.compile("(?m)^[-*]\\s+");
    private static final Pattern MARKDOWN_NUMBERED_LIST = Pattern.compile("(?m)^\\d+\\.\\s+");
    private static final Pattern EMOJI = Pattern.compile(
            "[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]|[\\u2600-\\u27BF]|[\\uFE0F\\u200D]");

    private TtsTextSanitizer() {
    }

    public static String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String result = text;
        result = removeNonSpeakableBlocks(result);
        result = stripIncompleteCustomProcessTag(result);
        result = removeMarkdownTables(result);
        result = HTML_TAG.matcher(result).replaceAll("");
        result = MARKDOWN_BOLD.matcher(result).replaceAll("$1");
        result = MARKDOWN_ITALIC.matcher(result).replaceAll("$1");
        result = MARKDOWN_CODE.matcher(result).replaceAll("$1");
        result = MARKDOWN_LINK.matcher(result).replaceAll("$1");
        result = MARKDOWN_HEADING.matcher(result).replaceAll("");
        result = MARKDOWN_BULLET_LIST.matcher(result).replaceAll("");
        result = MARKDOWN_NUMBERED_LIST.matcher(result).replaceAll("");
        result = EMOJI.matcher(result).replaceAll("");
        result = stripMarkdownSyntaxChars(result);
        result = normalizeSpeakableWhitespace(result);
        return result.strip();
    }

    /**
     * 流式合成过程中，若结构尚未完整（表格行、HTML 标签、成对星号等），暂不参与朗读。
     */
    static boolean shouldHoldForIncompleteStructure(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        if (!stripIncompleteCustomProcessTag(raw).equals(raw)) {
            return true;
        }
        if (hasIncompleteHtmlTag(raw)) {
            return true;
        }
        if (hasIncompleteTableLine(raw)) {
            return true;
        }
        return hasUnpairedBoldMarker(raw);
    }

    private static String removeMarkdownTables(String text) {
        String result = MARKDOWN_TABLE_LINE.matcher(text).replaceAll("");
        return MARKDOWN_TABLE_SEPARATOR.matcher(result).replaceAll("");
    }

    private static String stripMarkdownSyntaxChars(String text) {
        String result = text;
        result = result.replace("**", "");
        result = result.replace('*', ' ');
        result = result.replace('_', ' ');
        result = result.replace('`', ' ');
        result = result.replace('|', ' ');
        result = result.replace('#', ' ');
        return result;
    }

    private static String normalizeSpeakableWhitespace(String text) {
        String result = text.replace('\r', '\n');
        result = result.replaceAll("[ \\t\\f\\u00A0]+", " ");
        result = result.replaceAll("\\n{2,}", "。");
        result = result.replace('\n', '，');
        result = result.replaceAll("[，。！？；：]{2,}", "。");
        result = result.replaceAll("\\s{2,}", " ");
        return result;
    }

    private static boolean hasIncompleteHtmlTag(String raw) {
        int lastOpen = raw.lastIndexOf('<');
        if (lastOpen < 0) {
            return false;
        }
        return raw.indexOf('>', lastOpen) < 0;
    }

    private static boolean hasIncompleteTableLine(String raw) {
        String[] lines = raw.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            return line.contains("|");
        }
        return false;
    }

    private static boolean hasUnpairedBoldMarker(String raw) {
        int count = 0;
        for (int i = 0; i < raw.length() - 1; i++) {
            if (raw.charAt(i) == '*' && raw.charAt(i + 1) == '*') {
                count++;
                i++;
            }
        }
        return count % 2 != 0;
    }

    private static String removeNonSpeakableBlocks(String text) {
        String result = text;
        for (int i = 0; i < 3; i++) {
            String next = PLUGIN_WRAPPER_DIV.matcher(result).replaceAll("");
            next = MARKDOWN_CUSTOM_PROCESS_BLOCK.matcher(next).replaceAll("");
            next = MARKDOWN_CUSTOM_PROCESS_SELF.matcher(next).replaceAll("");
            next = SCRIPT_STYLE_BLOCK.matcher(next).replaceAll("");
            if (next.equals(result)) {
                break;
            }
            result = next;
        }
        return result;
    }

    static String stripIncompleteCustomProcessTag(String text) {
        int start = indexOfIgnoreCase(text, "<markdown-custom-process");
        if (start < 0) {
            return text;
        }
        if (indexOfIgnoreCase(text, "</markdown-custom-process>", start) >= 0) {
            return text;
        }
        return text.substring(0, start);
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        return indexOfIgnoreCase(text, needle, 0);
    }

    private static int indexOfIgnoreCase(String text, String needle, int fromIndex) {
        return text.toLowerCase().indexOf(needle.toLowerCase(), fromIndex);
    }
}
