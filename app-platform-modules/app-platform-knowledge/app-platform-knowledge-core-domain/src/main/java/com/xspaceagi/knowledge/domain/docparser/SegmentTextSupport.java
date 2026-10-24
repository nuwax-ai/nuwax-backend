package com.xspaceagi.knowledge.domain.docparser;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能分段-纯文本切分工具集(静态): 句子粗分/标题识别/兜底切分/分段合并。
 */
@Slf4j
public class SegmentTextSupport {

    /**
     * 粗粒度分段：按句末标点（句号、感叹号等）或双换行符切分，保证不在句子中间断句。
     * 如果分不出来，兜底按字数硬切分。
     * 分段优先级：1.句末标点 > 2.双换行符 > 3.单换行符 > 4.硬切分
     * @param text 原始文本
     * @param targetSize 目标分段字数（约8000字）
     * @return 粗分段结果，保证不遗漏任何文字
     */
    static List<String> coarseSplitBySentence(String text, int targetSize) {
        if (text == null || text.isEmpty()) {
            List<String> result = new ArrayList<>();
            if (text != null) {
                result.add(text);
            }
            return result;
        }

        if (text.length() <= targetSize) {
            log.info("coarseSplitBySentence: text {} chars <= targetSize {}, no split needed", text.length(), targetSize);
            List<String> result = new ArrayList<>();
            result.add(text);
            return result;
        }

        log.info("coarseSplitBySentence: splitting {} chars with targetSize {}", text.length(), targetSize);
        List<String> segments = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + targetSize, text.length());

            if (end < text.length()) {
                // 搜索范围：[targetSize*0.8, targetSize*1.2]，从后向前寻找最佳分段点
                int searchStart = start + (int)(targetSize * 0.8);
                int searchEnd = Math.min(start + (int)(targetSize * 1.2), text.length() - 1);
                int bestPos = -1;

                // 优先级0：章节标题边界（最语义化的分段点）
                // 优先找顶层章节（如“四、”、“第X章”），再找子节（如“4.1”）
                int chMin = start + (int)(targetSize * 0.5);
                int chMax = Math.min(start + (int)(targetSize * 1.2), text.length() - 1);
                int targetPos = start + targetSize;
                // 第一轮：从targetPos向左搜索顶层章节标题
                for (int pos = Math.min(targetPos, chMax); pos >= chMin; pos--) {
                    if (isTopLevelChapterBoundaryAt(text, pos)) { bestPos = pos; break; }
                }
                if (bestPos == -1) {
                    for (int pos = targetPos + 1; pos <= chMax; pos++) {
                        if (isTopLevelChapterBoundaryAt(text, pos)) { bestPos = pos; break; }
                    }
                }
                // 第二轮：没找到顶层，搜索任意章节标题（包括子节）
                if (bestPos == -1) {
                    for (int pos = Math.min(targetPos, chMax); pos >= chMin; pos--) {
                        if (isChapterBoundaryAt(text, pos)) { bestPos = pos; break; }
                    }
                }
                if (bestPos == -1) {
                    for (int pos = targetPos + 1; pos <= chMax; pos++) {
                        if (isChapterBoundaryAt(text, pos)) { bestPos = pos; break; }
                    }
                }

                // 优先级1：寻找句末标点（。！？；.!?;）
                // 跳过章节编号中的.（如1.2、1.1.1、1.标题）
                if (bestPos == -1) {
                    for (int pos = searchEnd; pos >= searchStart; pos--) {
                        char c = text.charAt(pos);
                        if (c == '\u3002' || c == '\uff01' || c == '\uff1f' || c == '\uff1b'
                                || c == '.' || c == '!' || c == '?' || c == ';') {
                            if (isRealSentenceEnd(text, pos)) {
                                bestPos = pos + 1;
                                break;
                            }
                        }
                    }
                }

                // 优先级2：寻找双换行符
                if (bestPos == -1) {
                    for (int pos = searchEnd; pos >= searchStart; pos--) {
                        if (pos + 1 < text.length() && text.charAt(pos) == '\n' && text.charAt(pos + 1) == '\n') {
                            bestPos = pos + 2;
                            break;
                        }
                    }
                }

                // 优先级3：寻找单换行符
                if (bestPos == -1) {
                    for (int pos = searchEnd; pos >= searchStart; pos--) {
                        if (text.charAt(pos) == '\n') {
                            bestPos = pos + 1;
                            break;
                        }
                    }
                }

                // 使用找到的分段点，否则在targetSize处硬切分（兜底）
                if (bestPos > start) {
                    end = bestPos;
                }
                // bestPos == -1 时 end 保持为 start + targetSize（硬切分）
            }

            String segment = text.substring(start, end);
            // 始终添加分段，保证不遗漏任何文字（包括空白）
            segments.add(segment);
            // 打印每个分段的开始和结束文字，便于检查
            String segStart = segment.substring(0, Math.min(50, segment.length())).replace("\n", "|");
            String segEnd = segment.substring(Math.max(0, segment.length() - 50)).replace("\n", "|");
            log.info("coarseSplitBySentence: chunk {}, {} chars, START: [{}], END: [{}]",
                    segments.size(), segment.length(), segStart, segEnd);
            start = end;
        }

        // 验证覆盖率：确保没有遗漏任何文字
        int totalChars = 0;
        for (String seg : segments) {
            totalChars += seg.length();
        }
        log.info("coarseSplitBySentence: produced {} chunks, total coverage: {} chars (original: {} chars, match: {})",
                segments.size(), totalChars, text.length(), totalChars == text.length());

        return segments;
    }

    /**
     * 判断指定位置的字符是否为真正的句末标点（而非章节编号中的.）
     * 中文标点。！？；始终是句末，英文.需要检查前后文排除章节编号
     */
    /**
     * 判断指定位置是否为顶层章节标题边界
     * 顶层章节：一、二、 / 第X章节 / 单个数字+空格+标题 / # 或 ## 或 ###
     */
    static boolean isTopLevelChapterBoundaryAt(String text, int pos) {
        if (pos < 0 || pos >= text.length()) return false;
        if (pos > 0 && text.charAt(pos - 1) != '\n') return false;
        int lineEnd = text.indexOf('\n', pos);
        if (lineEnd < 0) lineEnd = text.length();
        String line = text.substring(pos, lineEnd).trim();
        if (line.isEmpty()) return false;
        // 中文序号：一、二、十二、
        if (line.matches("^[一二三四五六七八九十百]+、.*")) return true;
        // 第X章/节/篇/条
        if (line.matches("^第[一二三四五六七八九十百\\d]+[章节篇条].*")) return true;
        // 单个数字+空格+标题："1 前言"
        if (line.matches("^\\d+[\\s\u3000].*")) return true;
        // Markdown标题 # / ## / ###
        if (line.matches("^#{1,3}\\s.*")) return true;
        return false;
    }

    /**
     * 判断指定位置是否为任意章节标题边界（包括子节）
     * 匹配：一、 / 1.1 / 1.1.1 / 第X章 / （一） / ##
     */
    static boolean isChapterBoundaryAt(String text, int pos) {
        if (pos < 0 || pos >= text.length()) return false;
        if (pos > 0 && text.charAt(pos - 1) != '\n') return false;
        int lineEnd = text.indexOf('\n', pos);
        if (lineEnd < 0) lineEnd = text.length();
        String line = text.substring(pos, lineEnd).trim();
        return isChapterTitleLine(line);
    }

    /**
     * 判断一行文本是否为章节标题行
     */
    static boolean isChapterTitleLine(String text) {
        if (text == null || text.isEmpty()) return false;
        if (text.matches("^[一二三四五六七八九十百]+、.*")) return true;
        if (text.matches("^\\d+\\.\\d+.*")) return true;
        if (text.matches("^\\d+[\\s\u3000].*")) return true;
        if (text.matches("^第[一二三四五六七八九十百\\d]+[章节篇条].*")) return true;
        if (text.matches("^[（(][一二三四五六七八九十]+[）)].*")) return true;
        if (text.matches("^#{1,6}\\s.*")) return true;
        return false;
    }

    static boolean isRealSentenceEnd(String text, int pos) {
        if (pos < 0 || pos >= text.length()) return false;
        char c = text.charAt(pos);

        // 中文句末标点始终是句子结束
        if (c == '\u3002' || c == '\uff01' || c == '\uff1f' || c == '\uff1b') {
            return true;
        }

        // 英文句号需要检查是否属于章节编号
        if (c == '.') {
            return !isPartOfSectionNumber(text, pos);
        }

        // ! ? ; 始终作为句末
        return true;
    }

    /**
     * 判断指定位置的.是否属于章节编号模式
     * 匹配模式："1.2"、"1.1.1"、"1.标题"、"1. 标题"、"12.3"、"2.5.4"等
     * 规则：.前面是数字或后面是数字→章节编号
     *       .后跟空格+中文/字母且前面是数字→章节编号（如"1. 标题"）
     */
    static boolean isPartOfSectionNumber(String text, int pos) {
        if (pos < 0 || pos >= text.length() || text.charAt(pos) != '.') return false;

        // .前面是数字 → 可能是 "1.2"、"1.标题"、"12.3"
        boolean digitBefore = (pos > 0 && Character.isDigit(text.charAt(pos - 1)));

        // .后面是数字 → 可能是 "1.2"、"1.1.1"
        boolean digitAfter = (pos + 1 < text.length() && Character.isDigit(text.charAt(pos + 1)));

        // .后跟空格+中文/字母，且前面是数字 → "1. 标题"模式
        boolean titleAfterDotWithDigitBefore = false;
        if (digitBefore && pos + 1 < text.length()) {
            char next = text.charAt(pos + 1);
            if (next == ' ' || next == '\t' || next == '\u3000') {
                for (int i = pos + 2; i < Math.min(pos + 6, text.length()); i++) {
                    char ci = text.charAt(i);
                    if (ci == ' ' || ci == '\t') continue;
                    if (Character.isLetter(ci) || isChineseChar(ci)) {
                        titleAfterDotWithDigitBefore = true;
                    }
                    break;
                }
            }
        }

        return digitBefore || digitAfter || titleAfterDotWithDigitBefore;
    }

    /**
     * 判断字符是否为中文字符
     */
    static boolean isChineseChar(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    /**
     * 合并短段（小于指定长度的段与前一节合并）
     */
    static List<String> mergeShortSegments(List<String> segments, int minLength) {
        if (segments.isEmpty()) {
            return segments;
        }

        List<String> merged = new ArrayList<>();
        StringBuilder currentBuilder = new StringBuilder();

        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            String trimmedSegment = segment.trim();

            if (currentBuilder.length() == 0) {
                currentBuilder.append(trimmedSegment);
            } else {
                if (currentBuilder.length() < minLength) {
                    currentBuilder.append("\n\n").append(trimmedSegment);
                    log.debug("Merged short segment {} (current total: {} chars, target: {} chars)",
                            i, currentBuilder.length(), minLength);
                } else {
                    merged.add(currentBuilder.toString());
                    currentBuilder = new StringBuilder(trimmedSegment);
                    log.debug("Started new segment {} (previous was {} chars)", i,
                            merged.get(merged.size() - 1).length());
                }
            }
        }

        if (currentBuilder.length() > 0) {
            if (merged.size() > 0 && currentBuilder.length() < minLength) {
                String lastSegment = merged.get(merged.size() - 1);
                String mergedSegment = lastSegment + "\n\n" + currentBuilder.toString();
                merged.set(merged.size() - 1, mergedSegment);
                log.debug("Merged final short segment (last segment: {} -> {} chars)",
                        lastSegment.length(), mergedSegment.length());
            } else {
                merged.add(currentBuilder.toString());
            }
        }

        if (segments.size() != merged.size()) {
            log.info("Segment merging: {} -> {} segments", segments.size(), merged.size());
        }

        return merged;
    }

    /**
     * 降级分段方法（当LLM调用失败时使用）
     */
    static List<String> fallbackSegmentation(String content) {
        log.warn("Using fallback segmentation method");
        // 预处理：移除页码 + 合并PDF软换行
        content = removePageNumbers(content);
        content = normalizeLineBreaks(content);

        List<String> segments = new ArrayList<>();
        String[] paragraphs = content.split("\\n\\n+");

        StringBuilder currentSegment = new StringBuilder();
        int minSegmentLength = 200;
        int maxSegmentLength = 2000;

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (currentSegment.length() + trimmed.length() > maxSegmentLength && currentSegment.length() >= minSegmentLength) {
                segments.add(currentSegment.toString().trim());
                currentSegment = new StringBuilder();
            }

            currentSegment.append(trimmed).append("\n\n");
        }

        if (currentSegment.length() > 0) {
            segments.add(currentSegment.toString().trim());
        }

        log.info("Fallback segmentation produced {} segments", segments.size());
        return segments;
    }

    // ==================== 本地智能分段方法 ====================

    /**
     * 合并孤独的章节标题段
     * 如果一个分段只有一行且以 "N 标题" 格式开头（数字+空格，无小数点），
     * 则将其合并到下一个分段的开头，保持章节标题与内容的语义连贯性。
     */
    static List<String> mergeLonelyChapterTitles(List<String> segments) {
        if (segments == null || segments.size() <= 1) {
            return segments;
        }

        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < segments.size()) {
            String seg = segments.get(i).trim();

            // Check if this is a lonely chapter title: "N 标题" format, short, no subsections
            boolean isLonelyTitle = false;
            if (i < segments.size() - 1) {
                String firstLine = seg.split("\n")[0].trim();
                isLonelyTitle = firstLine.matches("^\\d+\\s+[\\u4e00-\\u9fff].*")
                        && !firstLine.matches("^\\d+\\.\\d+.*")
                        && seg.length() < 200;
            }

            if (isLonelyTitle) {
                // Merge with next segment
                String next = segments.get(i + 1);
                String merged = seg + "\n" + next;
                result.add(merged);
                log.debug("Merged lonely chapter title '{}' with next segment", seg.split("\n")[0].trim());
                i += 2; // Skip next since we merged it
            } else {
                result.add(segments.get(i));
                i++;
            }
        }

        return result;
    }

    /**
     * 将过短的分段向前合并（短分段合并到前一个分段中）
     * 如果第一个分段就很短，则向后合并到下一个分段
     */
    /**
     * 向前合并分段：将不满 targetSize 的分段与上一个分段合并
     * 保证每个分段尽量接近 targetSize，为第二阶段LLM提供足够的上下文
     * 第一段如果太短，合并到下一段；最后一段如果太短，合并到上一段
     */
    static List<String> mergeSegmentsUpToSize(List<String> segments, int targetSize) {
        if (segments == null || segments.size() <= 1) {
            return segments;
        }

        List<String> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String seg : segments) {
            if (buffer.length() == 0) {
                buffer.append(seg);
            } else if (buffer.length() + seg.length() <= targetSize) {
                buffer.append("\n").append(seg);
            } else {
                result.add(buffer.toString());
                buffer = new StringBuilder(seg);
            }
        }

        // 处理最后一段
        if (buffer.length() > 0) {
            if (!result.isEmpty() && buffer.length() < targetSize * 0.3) {
                result.set(result.size() - 1, result.get(result.size() - 1) + "\n" + buffer.toString());
            } else {
                result.add(buffer.toString());
            }
        }

        // 后处理：第一段如果太短（不到 targetSize 的30%），合并到第二段
        if (result.size() >= 2 && result.get(0).length() < targetSize * 0.3) {
            result.set(0, result.get(0) + "\n" + result.get(1));
            result.remove(1);
        }

        return result;
    }

    /**
     * 本地智能分段方法
     * 策略：先按章节关键词分割 -> 短段合并 -> 超长段按标点拆分 -> 标点兜底
     */
    static List<String> segmentByEmbedding(String text) {
        // 预处理：移除页码 + 合并PDF软换行
        text = removePageNumbers(text);
        text = removeTrailingTocPage(text);
        text = normalizeLineBreaks(text);

        final int targetSegmentSize = 1500;

        // Step 1: 尝试按章节关键词分割
        List<String> segments = splitByTitles(text);

        if (segments == null || segments.isEmpty()) {
            log.info("splitByTitles produced no segments, falling back to punctuation splitting");
            return splitByPunctuation(text, targetSegmentSize);
        }

        log.info("splitByTitles produced {} segments", segments.size());

        // Step 1.5: 合并孤独的章节标题段（如 "7 计量方法与结算原则" 只有标题没有内容）
        // 这些短标题段应与下一段（如 "7.1 交易计量..."）合并，保持语义完整
        segments = mergeLonelyChapterTitles(segments);
        log.info("After mergeLonelyChapterTitles: {} segments", segments.size());

        // Step 2: 向前合并 -- 不满 targetSegmentSize 的分段和上一个分段合并
        // 保证每个粗分段尽量接近 targetSegmentSize，给第二阶段LLM足够的上下文
        List<String> merged = mergeSegmentsUpToSize(segments, targetSegmentSize);
        log.info("After merging segments up to {}: {} segments", targetSegmentSize, merged.size());

        // Step 3: 拆分仍然超长的分段
        List<String> result = new ArrayList<>();

        for (String seg : merged) {
            if (seg.length() > targetSegmentSize) {
                List<String> subSegments = splitByPunctuation(seg, targetSegmentSize);
                /*
                if(subSegments != null && subSegments.size() > 0) {
                    for (String subSegment : subSegments) {
                        stringBuilder.append(subSegment);
                    }
                }
                result.add(stringBuilder.toString());*/
                result.addAll(subSegments);
            } else {
                result.add(seg);
            }
        }

        // Final merge: combine short segments but preserve large ones intact
        // (splitByPunctuation already handles splitting correctly, including tables)
        List<String> new_result = new ArrayList<>();
        if (result != null && result.size() > 0) {
            StringBuilder buffer = new StringBuilder();
            for (int i = 0; i < result.size(); i++) {
                String seg = result.get(i);
                // If adding this segment would exceed target and buffer is non-empty,
                // flush buffer first (but don't split the individual segment)
                if (buffer.length() > 0 && buffer.length() + seg.length() > targetSegmentSize * 1.5) {
                    new_result.add(buffer.toString());
                    buffer = new StringBuilder();
                }
                buffer.append(seg);
                // If buffer exceeds target and we're at a natural boundary, flush
                if (buffer.length() > targetSegmentSize && i < result.size() - 1) {
                    new_result.add(buffer.toString());
                    buffer = new StringBuilder();
                }
            }
            if (buffer.length() > 0) {
                new_result.add(buffer.toString());
            }
        }

        // Step 4: 后处理 - 合并过短的分段（<500字的非末尾分段与下一段合并）
        List<String> finalResult = mergeShortSegments(new_result, 500);
        log.info("Final segmentByEmbedding result: {} segments", finalResult.size());
        return finalResult;
    }

    // ==================== 章节标题识别方法 ====================

    /**
     * 通用分割方法：按正则Pattern在匹配位置切分文本
     * 匹配到的关键词保留在下一段的开头
     * 对匹配行做短行过滤（<=80字符），避免将正文中的章节引用误判为分割点
     */
    static List<String> splitByPattern(String text, Pattern pattern, String label) {
        Matcher matcher = pattern.matcher(text);
        List<String> segments = new ArrayList<>();
        int lastEnd = 0;

        while (matcher.find()) {
            String matchedLine = matcher.group().trim();
            // 短行过滤：标题行不超过80字符
            if (matchedLine.length() > 80) {
                continue;
            }
            String segment = text.substring(lastEnd, matcher.start());
            if (!segment.trim().isEmpty()) {
                segments.add(segment);
            }
            lastEnd = matcher.start();
        }

        if (lastEnd < text.length()) {
            String last = text.substring(lastEnd);
            if (!last.trim().isEmpty()) {
                segments.add(last);
            }
        }

        if (segments.size() > 2) {
            log.info("splitByPattern [{}]: produced {} segments", label, segments.size());
            return segments;
        }
        return null;
    }

    /**
     * 通过短行识别标题
     * 规则：非空行，长度<=20字符，不以句末标点结尾，非纯数字
     * 连续的短行只取第一个作为分割点
     */
    static List<String> splitByShortLines(String text) {
        String[] lines = text.split("\\n");
        List<Integer> splitPositions = new ArrayList<>();

        int pos = 0;
        boolean lastWasShort = false;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            int lineStart = text.indexOf(lines[i], pos);
            if (lineStart < 0) lineStart = pos;

            if (!trimmed.isEmpty()
                    && trimmed.length() <= 20
                    && !trimmed.matches(".*[。！？；.!?;]$")
                    && !trimmed.matches("^\\d+$")
                    && !lastWasShort) {
                splitPositions.add(lineStart);
                lastWasShort = true;
            } else {
                lastWasShort = false;
            }
            pos = lineStart + lines[i].length() + 1;
        }

        if (splitPositions.size() < 3) {
            return null;
        }

        List<String> segments = new ArrayList<>();
        for (int i = 0; i < splitPositions.size(); i++) {
            int start = splitPositions.get(i);
            int end = (i + 1 < splitPositions.size()) ? splitPositions.get(i + 1) : text.length();
            String seg = text.substring(start, end).trim();
            if (!seg.isEmpty()) {
                segments.add(seg);
            }
        }

        return segments;
    }

    /**
     * 多层级章节标题识别
     * 按优先级逐层尝试不同的章节模式，一旦匹配到足够的分段就返回
     * 所有模式都要求标题行长度<=80字符（在splitByPattern中过滤）
     */
    static List<String> splitByTitles(String text) {
        List<String> segments;

        // 层级1: Markdown标题 (# / ## / ###)
        segments = splitByPattern(text,
                Pattern.compile("^(#{1,2}\\s+.+)$", Pattern.MULTILINE),
                "markdown-heading");
        if (segments != null) return segments;

        // 层级2: 第X章 主章节格式（排除子部分如"第八章 .1"、"第十四章.8"）
        // 子部分特征：章后面紧跟"."或" ."加数字，排除这些，只匹配纯章节标题
        segments = splitByPattern(text,
                Pattern.compile("^第[一二三四五六七八九十百]+章(?!\\s*\\.\\d).+$", Pattern.MULTILINE),
                "chapter-zh-main");
        if (segments != null) return segments;

        // 层级3: 第X节 / 第X篇 格式
        segments = splitByPattern(text,
                Pattern.compile("^第[一二三四五六七八九十百]+[节篇].*$", Pattern.MULTILINE),
                "section-zh");
        if (segments != null) return segments;

        // 层级4: 一、二、三 格式
        segments = splitByPattern(text,
                Pattern.compile("^[一二三四五六七八九十]+、.{0,60}$", Pattern.MULTILINE),
                "chinese-number");
        if (segments != null) return segments;

        // 层级5: （一）（二）格式
        segments = splitByPattern(text,
                Pattern.compile("^[（(][一二三四五六七八九十]+[）)].{0,60}$", Pattern.MULTILINE),
                "chinese-paren");
        if (segments != null) return segments;

        // 层级5.5: 数字编号 1. / 1.1 格式（排除三级编号如2.3.1）
        // 优先按一级和二级编号分割，避免三级编号把文档切碎
        segments = splitByPattern(text,
                Pattern.compile("^\\d+(\\.\\d+)?[\\.\\s]\\s*[^\\d].{0,60}$", Pattern.MULTILINE),
                "numeric-section-2level");
        if (segments != null) return segments;

        // 层级6: 数字编号 1. / 1.1 / 2.3.1 格式（兜底，当5.5无法分割时使用）
        segments = splitByPattern(text,
                Pattern.compile("^\\d+(\\.\\d+)*[\\.\\s]\\s*[^\\d].{0,60}$", Pattern.MULTILINE),
                "numeric-section");
        if (segments != null) return segments;

        // 层级7: 短行标题
        segments = splitByShortLines(text);
        if (segments != null) return segments;

        return null;
    }

    // ==================== 标点兜底分割方法 ====================

    /**
     * 预处理：移除PDF提取文本中的页码
     * PDF页码以两种形式出现：
     * 1. 独立行页码：整行只有1-4位数字，如 "58"、"71"
     * 2. 行首内联页码：行首为1-3位数字紧跟空格和中文字符，如 "59 得资质认定"
     *
     * 注意：不会误删章节编号（如 "3.2"、"第3章"）和列表编号（如 "1."、"2."）
     */
    static String removePageNumbers(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();

            // Case 1: 独立行页码 - 整行只有1-3位数字
            // 排除纯数字年份（如 "2025"）- 只有1-3位数字才视为页码
            if (trimmed.matches("^\\d{1,3}$")) {
                continue;
            }

            // Case 2: 行首内联页码 - 以1-3位数字开头，紧跟空格和中文内容
            // 如 "59 得资质认定"、"71 告》（以下..."
            // 排除章节编号（如 "6 交易执行与偏差处理"这类数字+空格+标题格式）
            // 判断规则：数字<=30且后续文本像标题（不以句末标点结尾）时保留，视为章节编号
            if (trimmed.matches("^\\d{1,3}\\s+[\\u4e00-\\u9fff\\u300a\\uff08].*")) {
                // 提取行首数字
                java.util.regex.Matcher numMatcher = java.util.regex.Pattern.compile("^(\\d{1,3})\\s+").matcher(trimmed);
                if (numMatcher.find()) {
                    int num = Integer.parseInt(numMatcher.group(1));
                    String afterNum = trimmed.substring(numMatcher.end());
                    // 小数字(<=30)且后续文本不以句末标点结尾 -> 可能是章节编号，保留
                    boolean looksLikeChapterTitle = num <= 30
                            && afterNum.length() > 2
                            && !afterNum.matches(".*[。！？；.!?;]$")
                            && trimmed.length() <= 80;
                    if (!looksLikeChapterTitle) {
                        trimmed = trimmed.replaceFirst("^\\d{1,3}\\s+", "");
                        if (trimmed.isEmpty()) {
                            continue;
                        }
                    }
                }
            }

            result.append(trimmed).append("\n");
        }

        // 移除末尾多余的换行符
        String cleaned = result.toString();
        while (cleaned.endsWith("\n\n\n")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }

        return cleaned;
    }


    /**
     * 预处理：移除PDF末尾的重复目录页
     * 部分PDF在正文结束后会有独立的目录页，内容为纯章节标题列表。
     * 检测策略：从末尾向前扫描连续短行(<=30字符)，如果其中章节标题>=3个则截断。
     */
    static String removeTrailingTocPage(String text) {
        if (text == null || text.length() < 200) {
            return text;
        }

        String[] lines = text.split("\n", -1);
        int tocStart = lines.length;
        int consecutiveShortLines = 0;
        int titleLineCount = 0;

        for (int i = lines.length - 1; i >= 0; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            boolean isShortLine = trimmed.length() <= 30;
            boolean isTitleFormat = isChapterTitleFormat(trimmed);
            if (isShortLine) {
                consecutiveShortLines++;
                if (isTitleFormat) {
                    titleLineCount++;
                }
                tocStart = i;
            } else {
                break;
            }
        }

        if (consecutiveShortLines >= 5 && titleLineCount >= 3) {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < tocStart; i++) {
                result.append(lines[i]).append("\n");
            }
            String cleaned = result.toString().trim();
            log.info("Removed trailing TOC page: {} lines removed ({} short lines, {} title lines)",
                    lines.length - tocStart, consecutiveShortLines, titleLineCount);
            return cleaned;
        }
        return text;
    }

    static boolean isChapterTitleFormat(String text) {
        if (text == null || text.isEmpty()) return false;
        if (text.matches("^[一-二三四五六七八九十百]+、.*")) return true;
        if (text.matches("^\\d+(\\.\\d+)+\\s+\\S+.*")) return true;
        if (text.matches("^第[一-九十百]+[章节].*")) return true;
        if (text.matches("^[（(][一-九十]+[）)].*")) return true;
        return false;
    }

    /**
     * 预处理：合并PDF提取文本中的软换行
     * PDF文本在短语中间有硬换行（如 "第十六\n批"、"m\n3"），需要合并为完整句子。
     * 规则：
     * - 空行保留为段落边界
     * - 上一行以句末标点结尾时，当前行开始新段落
     * - 当前行以结构标记开头时（如章节号、GB标准号），开始新段落
     * - 其他情况合并到上一行（软换行续行）
     */
    static String normalizeLineBreaks(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();

            if (trimmed.isEmpty()) {
                // Empty line = paragraph boundary
                if (result.length() > 0) {
                    result.append("\n\n");
                }
                continue;
            }

            if (result.length() == 0) {
                result.append(trimmed);
                continue;
            }

            // Determine if this line should start a new paragraph
            boolean prevEndsPunct = endsWithSentencePunct(result.toString());
            boolean currentIsStructural = startsWithStructuralMarker(trimmed);

            if (prevEndsPunct || currentIsStructural) {
                // Start new paragraph
                result.append("\n").append(trimmed);
            } else {
                // Merge: soft wrap continuation
                result.append(trimmed);
            }
        }

        return result.toString();
    }

    /**
     * 判断文本末尾是否为句末标点
     * 查找最后一个非空白字符，判断是否为句末标点或闭合括号
     */
    static boolean endsWithSentencePunct(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            return "\u3002\uff01\uff1f\uff1b.!?;\u300b\u300d\uff09)\u3011]".indexOf(c) >= 0;
        }
        return false;
    }

    /**
     * Detect table header line: starts with "\u8868" + digit (e.g., "\u88681.7-1")
     */
    static boolean isTableHeader(String line) {
        if (line == null || line.isEmpty()) return false;
        return line.matches("^表\\s*\\d+.*");
    }

    /**
     * Detect table data row: contains tab chars or 3+ numeric values,
     * does NOT end with sentence punctuation.
     */
    static boolean isTableDataRow(String line) {
        if (line == null || line.isEmpty()) return false;
        if (line.matches(".*[。！.!]$")) return false;
        if (line.contains("\t")) return true;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+\\.\\d+").matcher(line);
        int count = 0;
        while (m.find() && count < 3) count++;
        return count >= 3;
    }

    /**
     * 判断文本是否以结构标记开头
     * 结构标记表示新段落/新章节的开始，不应与上一行合并
     */
    static boolean startsWithStructuralMarker(String text) {
        if (text == null || text.isEmpty()) return false;
        // Section numbers: 1.2, 3.3, 12.1.3
        if (text.matches("^\\d+\\.\\d+.*")) return true;
        // Chinese number markers: 一、二、
        if (text.matches("^[一二三四五六七八九十]+、.*")) return true;
        // Chapter/section: 第X章/节/条
        if (text.matches("^第[一二三四五六七八九十百]+[章节篇条].*")) return true;
        // GB/JB standard numbers
        if (text.matches("^(GB|JB|HG|SH|DL)\\d+.*")) return true;
        // Parenthesized numbers: （一）（二）
        if (text.matches("^[（(][一二三四五六七八九十]+[）)].*")) return true;
        // Figure/table references: 图1, 表1, 图 1-1
        if (text.matches("^图\\s*\\d.*") || text.matches("^表\\s*\\d.*")) return true;
        // Markdown headers
        if (text.matches("^#{1,6}\\s.*")) return true;
        // 注/Note/说明
        if (text.matches("^注\\s*\\d*[:：]?.*") || text.matches("^说明[:：]?.*")) return true;
        return false;
    }

    /**
     * 按标点符号进行分段（兜底策略）
     * 逐行累积，在段落结束（句末标点后有空行）且累积长度超过 targetSize*70% 时切分
     */
    static List<String> splitByPunctuation(String text, int targetSize) {
        // 预处理：移除页码 + 合并PDF软换行（双重保险）
        text = removePageNumbers(text);
        text = removeTrailingTocPage(text);
        text = normalizeLineBreaks(text);

        if (text.length() <= targetSize) {
            List<String> result = new ArrayList<>();
            result.add(text);
            return result;
        }

        List<String> segments = new ArrayList<>();
        String[] lines = text.split("\n");
        StringBuilder current = new StringBuilder();

        boolean inTable = false;
        for (String line : lines) {
            String trimmed = line.trim();

            // 跳过空行和孤立数字行（PDF页码），不作为切分依据
            if (trimmed.isEmpty() || trimmed.matches("^\\d{1,4}$")) {
                if (inTable) inTable = false;
                continue;
            }

            // Detect table region
            if (isTableHeader(trimmed)) {
                inTable = true;
            } else if (inTable && !isTableDataRow(trimmed)) {
                inTable = false;
            } else if (!inTable && isTableDataRow(trimmed)) {
                inTable = true;
            }

            current.append(trimmed).append("\n");

            // Skip splitting inside table region
            if (inTable) continue;

            // 只在句末标点处切分：累积超过 targetSize*0.8 且当前行以句末标点结尾时才切
            if (current.length() >= targetSize * 0.8
                    && trimmed.matches(".*[。！.!]$")) {
                segments.add(current.toString());
                current = new StringBuilder();
            }

            // 安全阀：累积超过 2*targetSize 时强制切分
            if (current.length() >= targetSize * 2) {
                segments.add(current.toString());
                current = new StringBuilder();
            }
        }

        if (current.length() > 0) {
            // 如果剩余内容较短，合并到上一个分段
            String remaining = current.toString().trim();
            if (!remaining.isEmpty()) {
                if (!segments.isEmpty()
                        && segments.get(segments.size() - 1).length() + remaining.length() <= targetSize * 1.5) {
                    segments.set(segments.size() - 1,
                            segments.get(segments.size() - 1) + "\n" + remaining);
                } else {
                    segments.add(remaining);
                }
            }
        }

        // Don't force-split if the single segment is table data (preserve table integrity)
        if (segments.size() == 1 && segments.get(0).length() > targetSize * 2
                && !isTableDataRow(segments.get(0))) {
            segments = forceSplitBySentenceEnd(text, targetSize);
        }

        return segments;
    }

    /**
     * 按句末标点强制切分（最终兜底）
     */
    static List<String> forceSplitBySentenceEnd(String text, int targetSize) {
        List<String> segments = new ArrayList<>();
        Pattern sentenceEnd = Pattern.compile("[。！]");
        Matcher matcher = sentenceEnd.matcher(text);

        int lastCut = 0;
        while (matcher.find()) {
            int endPos = matcher.end();
            if (endPos - lastCut >= targetSize) {
                segments.add(text.substring(lastCut, endPos));
                lastCut = endPos;
            }
        }

        if (lastCut < text.length()) {
            String remaining = text.substring(lastCut);
            if (!remaining.trim().isEmpty()) {
                if (segments.isEmpty()) {
                    segments.addAll(forceHardSplit(text, targetSize));
                } else {
                    String lastSeg = segments.get(segments.size() - 1);
                    if (lastSeg.length() + remaining.length() <= targetSize * 1.5) {
                        segments.set(segments.size() - 1, lastSeg + remaining);
                    } else {
                        segments.add(remaining);
                    }
                }
            }
        }

        return segments;
    }

    /**
     * 硬切分：按固定长度切分，在最近的换行符处对齐
     */
    static List<String> forceHardSplit(String text, int targetSize) {
        List<String> segments = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + targetSize, text.length());
            if (end < text.length()) {
                int lastNl = text.lastIndexOf('\n', end);
                if (lastNl > start + targetSize / 2) {
                    end = lastNl + 1;
                }
            }
            segments.add(text.substring(start, end));
            start = end;
        }
        return segments;
    }
}
