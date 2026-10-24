package com.xspaceagi.knowledge.domain.docparser;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.knowledge.domain.dto.SmartParsingSegments;
import com.xspaceagi.knowledge.domain.dto.SmartParsingTopicGenDto;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 智能分段-LLM结果解析与模糊位置匹配(静态): JSON清洗/位置校验/精确与模糊匹配/按位置切分。
 */
@Slf4j
public class SegmentPositionMatcher {

    // 🎯 模糊范围配置参数
    private static final int MIN_FUZZY_RANGE = 50;           // 最小搜索范围：±50字符
    private static final int MAX_FUZZY_RANGE = 500;          // 最大搜索范围：±500字符
    private static final double BASE_RANGE_PERCENTAGE = 0.05; // 基础范围百分比 (5%)
    private static final double CONFIDENCE_THRESHOLD = 0.3;  // 最低置信度阈值
    private static final int EXACT_MATCH_LENGTH = 20;

    static List<SegmentPositionInfo> convertToSegmentPositionInfo(List<SmartParsingSegments> segmentList) {
        List<SegmentPositionInfo> segmentPositions = new ArrayList<>();

        if (segmentList == null || segmentList.isEmpty()) {
            return segmentPositions;
        }

        for (SmartParsingSegments seg : segmentList) {
            if (seg.getPosition() != null && seg.getStartText() != null) {
                SegmentPositionInfo info = new SegmentPositionInfo();
                info.llmPosition = seg.getPosition();
                info.matchedPosition = seg.getPosition();
                info.startText = seg.getStartText();
                segmentPositions.add(info);
            } else {
                log.warn("Skipping segment with null position or startText: {}", seg);
            }
        }

        return segmentPositions;
    }

    // ==================== 🎯 模糊范围 + 内容匹配核心方法 ====================

    /**
     * 🎯 核心方法：使用模糊匹配策略解析分段信息
     * 基于用户建议：LLM位置作为模糊范围指示器，通过内容匹配确定边界
     * 修改为接受SmartParsingTopicGenDto对象，适配新的DTO返回类型
     */
    static List<String> parseSegmentsUsingFuzzyStrategy(SmartParsingTopicGenDto result, String originalContent) {
        try {
            // 从DTO对象获取分段信息
            List<SmartParsingSegments> segmentList = result.getSegments();
            if (segmentList == null || segmentList.isEmpty()) {
                log.warn("No segments found in DTO result");
                return new ArrayList<>();
            }

            // 转换为SegmentPositionInfo列表
            List<SegmentPositionInfo> segmentPositions = convertToSegmentPositionInfo(segmentList);

            log.info("Successfully converted {} segments from DTO to SegmentPositionInfo", segmentPositions.size());

            if (segmentPositions.isEmpty()) {
                log.warn("No valid segments found after conversion");
                return new ArrayList<>();
            }

            // 🎯 核心：使用模糊范围匹配确定最终位置
            for (SegmentPositionInfo info : segmentPositions) {
                // 计算模糊范围
                FuzzyRange range = calculateFuzzyRange(info.llmPosition, originalContent.length());

                // 在范围内搜索最佳匹配
                MatchResult matchResult = findBestMatchInRange(originalContent, info.startText, range);

                info.matchedPosition = matchResult.position;
                info.matchConfidence = matchResult.confidenceScore;
                info.searchRange = range;
                info.matchedText = matchResult.matchedText;
                info.matchStrategy = matchResult.matchStrategy;

                log.debug("Segment: llmPos={}, matchPos={}, confidence={:.2f}, strategy={}, range={}",
                        info.llmPosition, info.matchedPosition, info.matchConfidence,
                        info.matchStrategy, range);
            }

            // 验证分段质量
            if (!validateSegmentQuality(segmentPositions)) {
                log.warn("Segment quality validation failed, some segments may have low confidence");
            }

            // 根据匹配位置进行分段
            return segmentByMatchedPositions(originalContent, segmentPositions, result);

        } catch (Exception e) {
            log.error("Failed to parse segments using fuzzy strategy", e);
            return new ArrayList<>();
        }
    }

    /**
     * 🎯 计算模糊搜索范围
     * 基于字符数计算，范围为LLM位置±baseRange
     */
    static FuzzyRange calculateFuzzyRange(int llmPosition, int contentLength) {
        // 计算基础范围：内容长度的5%，但在50-500字符之间
        int baseRange = (int)(contentLength * BASE_RANGE_PERCENTAGE);
        baseRange = Math.max(MIN_FUZZY_RANGE, Math.min(MAX_FUZZY_RANGE, baseRange));

        // 计算搜索范围：LLM位置±baseRange
        int startIndex = Math.max(0, llmPosition - baseRange);
        int endIndex = Math.min(contentLength, llmPosition + baseRange);
        int rangeSize = endIndex - startIndex;

        // 边界适应：靠近文档边界时扩大范围
        if (llmPosition < contentLength * 0.1) {
            endIndex = Math.min(contentLength, llmPosition + baseRange * 2);
        } else if (llmPosition > contentLength * 0.9) {
            startIndex = Math.max(0, llmPosition - baseRange * 2);
        }

        return new FuzzyRange(llmPosition, startIndex, endIndex, endIndex - startIndex);
    }

    /**
     * 🎯 在模糊范围内搜索最佳匹配
     * 实现多层级匹配策略
     */
    static MatchResult findBestMatchInRange(String content, String searchText, FuzzyRange range) {
        if (searchText == null || searchText.trim().isEmpty()) {
            return new MatchResult(range.centerPosition, 0.0, "", "empty_search");
        }

        String trimmedSearch = searchText.trim();
        List<MatchResult> results = new ArrayList<>();

        // 层级1: 精确匹配 (置信度1.0)
        MatchResult exactMatch = tryExactMatch(content, trimmedSearch, range);
        return exactMatch;
        /*
        if (exactMatch.confidenceScore >= 1.0) {
            log.debug("Exact match found at position {}", exactMatch.position);
            return exactMatch;
        }
        results.add(exactMatch);

        // 层级2: 规范化匹配 (置信度0.8-0.9)
        MatchResult normalizedMatch = tryNormalizedMatch(content, trimmedSearch, range);
        results.add(normalizedMatch);

        // 层级3: 部分匹配 (置信度0.6-0.7)
        MatchResult partialMatch = tryPartialMatch(content, trimmedSearch, range);
        results.add(partialMatch);

        // 层级4: 相似度匹配 (置信度0.4-0.6)
        MatchResult similarityMatch = trySimilarityMatch(content, trimmedSearch, range);
        results.add(similarityMatch);

        // 返回置信度最高的结果
        MatchResult bestMatch = results.stream()
                .max((r1, r2) -> Double.compare(r1.confidenceScore, r2.confidenceScore))
                .orElse(new MatchResult(range.centerPosition, 0.0, trimmedSearch, "fallback"));

        log.debug("Best match in range {}: strategy={}, confidence={:.2f}, position={}",
                range, bestMatch.matchStrategy, bestMatch.confidenceScore, bestMatch.position);

        return bestMatch;*/
    }

    /**
     * 🎯 尝试精确匹配
     */
    static MatchResult tryExactMatch(String content, String searchText, FuzzyRange range) {
        // 1. Direct exact match
        int exactIndex = content.indexOf(searchText);
        if (exactIndex >= 0) {
            //String testStr = content.substring(exactIndex, exactIndex + searchText.length());
            String testStr = content.substring(exactIndex, exactIndex + searchText.length() > content.length()?content.length():exactIndex + searchText.length());
            //System.out.println("s1:" + searchText);
            //System.out.println("s2:" + testStr);
            if(!testStr.equals(searchText)) {
                //System.out.println("s3:" + testStr);
            }
            return new MatchResult(exactIndex, 1.0, searchText, "exact");
        }

        String tt_str = new String(searchText.replace("\n", "\n\r"));
        exactIndex = content.indexOf(tt_str);
        if (exactIndex >= 0) {
            //String testStr = content.substring(exactIndex, exactIndex + searchText.length());
            String testStr = content.substring(exactIndex, exactIndex + searchText.length() > content.length()?content.length():exactIndex + searchText.length());
            //System.out.println("s1:" + searchText);
            //System.out.println("s2:" + testStr);
            if(!testStr.equals(searchText)) {
                //System.out.println("s3:" + testStr);
            }
            return new MatchResult(exactIndex, 1.0, searchText, "exact2");
        }

        String tt_str2 = new String(searchText.replace("\n", "\n\n"));
        exactIndex = content.indexOf(tt_str2);
        if (exactIndex >= 0) {
            //String testStr = content.substring(exactIndex, exactIndex + searchText.length());
            String testStr = content.substring(exactIndex, exactIndex + searchText.length() > content.length()?content.length():exactIndex + searchText.length());
            //System.out.println("s1:" + searchText);
            //System.out.println("s2:" + testStr);
            if(!testStr.equals(searchText)) {
                //System.out.println("s3:" + testStr);
            }
            return new MatchResult(exactIndex, 1.0, searchText, "exact2");
        }

        String tt_str3 = searchText.substring(0, searchText.length()/2);
        exactIndex = content.indexOf(tt_str3);
        if (exactIndex >= 0) {
            String testStr = content.substring(exactIndex, exactIndex + searchText.length() > content.length()?content.length():exactIndex + searchText.length());
            //System.out.println("s1:" + searchText);
            //System.out.println("s2:" + testStr);
            if(!testStr.equals(searchText)) {
                //System.out.println("s3:" + testStr);
            }
            return new MatchResult(exactIndex, 1.0, searchText, "exact3");
        }

        String tt_str4 = searchText.substring(0, tt_str3.length()/2);
        if(tt_str4.length() > 20) {
            //String tt_str4 = searchText.substring(0, tt_str3.length()/2);
            exactIndex = content.indexOf(tt_str4);
            if (exactIndex >= 0) {
                //String testStr = content.substring(exactIndex, exactIndex + searchText.length());
                String testStr = content.substring(exactIndex, exactIndex + searchText.length() > content.length()?content.length():exactIndex + searchText.length());
                //System.out.println("s1:" + searchText);
                //System.out.println("s2:" + testStr);
                if(!testStr.equals(searchText)) {
                    //System.out.println("s3:" + testStr);
                }
                return new MatchResult(exactIndex, 1.0, searchText, "exact3");
            }

            exactIndex = content.indexOf(tt_str4.replace("\n", "\n\r"));
            if (exactIndex >= 0) {
                //String testStr = content.substring(exactIndex, exactIndex + searchText.length());
                String testStr = content.substring(exactIndex, exactIndex + searchText.length() > content.length()?content.length():exactIndex + searchText.length());
                //System.out.println("s1:" + searchText);
                //System.out.println("s2:" + testStr);
                if(!testStr.equals(searchText)) {
                    //System.out.println("s3:" + testStr);
                }
                return new MatchResult(exactIndex, 1.0, searchText, "exact3");
            }


        }

        String tt_str5 = searchText.substring(0, tt_str4.length()/2);
        if(tt_str5.length() > 20) {
            exactIndex = content.indexOf(tt_str5);
            if (exactIndex >= 0) {
                //String testStr = content.substring(exactIndex, exactIndex + searchText.length());
                String testStr = content.substring(exactIndex, exactIndex + searchText.length() > content.length()?content.length():exactIndex + searchText.length());
                //System.out.println("s1:" + searchText);
                //System.out.println("s2:" + testStr);
                if(!testStr.equals(searchText)) {
                    //System.out.println("s3:" + testStr);
                }
                return new MatchResult(exactIndex, 1.0, searchText, "exact3");
            }
            //.replace("\n", "\n\r")
            exactIndex = content.indexOf(tt_str5.replace("\n", "\n\r"));
            if (exactIndex >= 0) {
                //String testStr = content.substring(exactIndex, exactIndex + searchText.length());
                String testStr = content.substring(exactIndex, exactIndex + searchText.length() > content.length()?content.length():exactIndex + searchText.length());
                //System.out.println("s1:" + searchText);
                //System.out.println("s2:" + testStr);
                if(!testStr.equals(searchText)) {
                    //System.out.println("s3:" + testStr);
                }

                return new MatchResult(exactIndex, 1.0, searchText, "exact3");
            }



        }

        if(tt_str5.length() > 15) {
            tt_str5 = tt_str5.substring(0, 15);
        }

        for(int i=0; i<tt_str5.length(); i++) {
            String keyword = new String(tt_str5.substring(0, i+1) + "\n" + tt_str5.substring(i+1, tt_str5.length()));
            if(content.indexOf(keyword) >= 0) {
                exactIndex = content.indexOf(keyword);
                return new MatchResult(exactIndex, 1.0, searchText, "exact4");
            }
        }


        // 2. Fallback: handle line ending mismatch (content has \r\n, searchText has \n)
        //    Build a normalized content copy and a position mapping back to original
        if(content.indexOf(tt_str5) >= 0) {
            exactIndex = content.indexOf(tt_str5);
            return new MatchResult(exactIndex, 1.0, searchText, "exact5");
        }

        boolean contentHasCR = content.indexOf('\r') >= 0;
        if (!contentHasCR) {
            return new MatchResult(range.centerPosition, 0.0, searchText, "no_exact");
        }

        // Normalize searchText: \r\n -> \n
        String normalizedSearch = searchText.replace("\r\n", "\n");

        // Build normalized content copy + position mapping
        // normPosToOrigPos[i] = position in original content for normalized position i
        // Content itself is NOT modified
        StringBuilder normBuf = new StringBuilder(content.length());
        int[] normPosToOrigPos = new int[content.length() + 1];
        int oPos = 0;
        while (oPos < content.length()) {
            normPosToOrigPos[normBuf.length()] = oPos;
            if (oPos + 1 < content.length()
                    && content.charAt(oPos) == '\r' && content.charAt(oPos + 1) == '\n') {
                normBuf.append('\n');
                oPos += 2;
            } else {
                normBuf.append(content.charAt(oPos));
                oPos++;
            }
        }
        normPosToOrigPos[normBuf.length()] = content.length();
        String normContent = normBuf.toString();

        // Search in normalized copy
        int normIdx = normContent.indexOf(normalizedSearch);
        if (normIdx >= 0) {
            int origPosition = normPosToOrigPos[normIdx];
            log.debug("tryExactMatch: line-ending normalized match at normPos={}, origPos={}", normIdx, origPosition);
            return new MatchResult(origPosition, 0.95, searchText, "exact_line_normalized");
        }

        return new MatchResult(range.centerPosition, 0.0, searchText, "no_exact");
    }

    /**
     * 🎯 尝试规范化匹配
     */
    static MatchResult tryNormalizedMatch(String content, String searchText, FuzzyRange range) {
        // 规范化：移除空格和标点符号
        String normalizedSearch = searchText.replaceAll("[\\s\\p{Punct}]+", "");

        int searchEnd = Math.min(range.endIndex, content.length());

        // 在范围内逐步搜索规范化匹配
        for (int i = range.startIndex; i <= searchEnd - EXACT_MATCH_LENGTH; i++) {
            int windowEnd = Math.min(i + normalizedSearch.length() + 100, searchEnd);
            String window = content.substring(i, windowEnd);
            String normalizedWindow = window.replaceAll("[\\s\\p{Punct}]+", "");

            if (normalizedWindow.contains(normalizedSearch)) {
                // 找到匹配，计算置信度
                int matchLength = Math.min(normalizedSearch.length(), 30); // 使用前30字符计算
                double confidence = 0.8 + (0.1 * matchLength / Math.max(searchText.length(), 1));
                confidence = Math.min(0.9, confidence);

                return new MatchResult(i, confidence, searchText.substring(0, Math.min(20, searchText.length())), "normalized");
            }
        }

        return new MatchResult(range.centerPosition, 0.0, searchText, "no_normalized");
    }

    /**
     * 🎯 尝试部分匹配
     */
    static MatchResult tryPartialMatch(String content, String searchText, FuzzyRange range) {
        // 使用搜索文本的前10-20个字符进行部分匹配
        int partialLength = Math.min(EXACT_MATCH_LENGTH, searchText.length());
        String partialSearch = searchText.substring(0, partialLength);

        int searchEnd = Math.min(range.endIndex, content.length());

        for (int i = range.startIndex; i <= searchEnd - partialLength; i++) {
            if (i + partialLength <= content.length()) {
                String window = content.substring(i, i + partialLength);

                // 检查是否匹配
                if (window.equals(partialSearch)) {
                    double confidence = 0.6 + (0.1 * (double)partialLength / searchText.length());
                    confidence = Math.min(0.7, confidence);

                    return new MatchResult(i, confidence, partialSearch, "partial");
                }
            }
        }

        return new MatchResult(range.centerPosition, 0.0, searchText, "no_partial");
    }

    /**
     * 🎯 尝试相似度匹配
     */
    static MatchResult trySimilarityMatch(String content, String searchText, FuzzyRange range) {
        // 计算文本相似度的简化版本
        int searchEnd = Math.min(range.endIndex, content.length());
        double bestSimilarity = 0.0;
        int bestPosition = range.centerPosition;

        // 在范围内滑动窗口计算相似度
        int windowSize = Math.min(searchText.length(), 50); // 最多50字符窗口

        for (int i = range.startIndex; i <= searchEnd - windowSize; i++) {
            if (i + windowSize <= content.length()) {
                String window = content.substring(i, i + windowSize);
                double similarity = calculateSimilarity(searchText.substring(0, windowSize), window);

                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    bestPosition = i;
                }
            }
        }

        double confidence = bestSimilarity * 0.6; // 最高0.6置信度
        return new MatchResult(bestPosition, confidence, searchText.substring(0, Math.min(20, searchText.length())), "similarity");
    }

    /**
     * 🎯 计算文本相似度（简化版）
     */
    static double calculateSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null) return 0.0;

        int maxLength = Math.max(text1.length(), text2.length());
        if (maxLength == 0) return 1.0;

        int matches = 0;
        int compareLength = Math.min(text1.length(), text2.length());

        for (int i = 0; i < compareLength; i++) {
            if (text1.charAt(i) == text2.charAt(i)) {
                matches++;
            }
        }

        return (double)matches / maxLength;
    }

    /**
     * 🎯 根据匹配位置进行精确分段
     * 关键改进：
     * 1. 起始位置使用模型识别的matchedPosition（保持章节划分能力）
     * 2. 结束位置根据下一次开始位置计算（保证数据不丢失）
     * 3. 通过调整起始位置避免内容重叠
     */
    static List<String> segmentByMatchedPositions(String content, List<SegmentPositionInfo> segmentPositions, SmartParsingTopicGenDto result) {
        List<String> finalSegments = new ArrayList<>();

        // 按匹配位置排序
        /*
        segmentPositions.sort((a, b) -> {
            if (a.matchedPosition == null && b.matchedPosition == null) return 0;
            if (a.matchedPosition == null) return 1;
            if (b.matchedPosition == null) return -1;
            return Integer.compare(a.matchedPosition, b.matchedPosition);
        });*/

        // 🔧 跟踪上一段的结束位置，用于避免内容重叠
        segmentPositions = sortSegmentPositionInfo(segmentPositions);
        int previousEndPosition = 0;

        int preEndPositionTest = 0;
        String preEndPositionTestZoom = "";
        for (int i = 0; i < segmentPositions.size(); i++) {
            SegmentPositionInfo info = segmentPositions.get(i);

            // 🔧 起始位置：使用模型识别的matchedPosition（保持章节划分能力）
            // 但如果造成重叠，则调整为上一段的结束位置（避免内容重复）
            int startPosition = info.matchedPosition;

            // 如果起始位置在上一段结束位置之前，则调整为上一段的结束位置（避免重叠）
            /*
            if (startPosition < previousEndPosition) {
                log.debug("Segment {} start position {} is before previous end position {}, adjusting to {} to avoid overlap",
                        i, startPosition, previousEndPosition, previousEndPosition);
                startPosition = previousEndPosition;
            }*/

            // 🔧 关键修复2：结束位置根据下一次开始位置计算（保证数据不丢失）
            int endPosition;
            if (i < segmentPositions.size() - 1) {
                // 不是最后一个分段，使用下一个段落的matchedPosition作为结束位置
                SegmentPositionInfo nextInfo = segmentPositions.get(i + 1);
                endPosition = nextInfo.matchedPosition != null ? nextInfo.matchedPosition : content.length();

                log.debug("Segment {} end position: using next segment's matchedPosition={}",
                        i, endPosition);
            } else {
                // 最后一个分段，到文档结尾
                endPosition = content.length();
                log.debug("Segment {} (last) end position: document end ({})", i, endPosition);
            }

            // 🔧 关键修复3：确保endPosition不超过内容长度
            if (endPosition > content.length()) {
                log.warn("Segment {} end position {} exceeds content length {}, limiting to content length",
                        i, endPosition, content.length());
                endPosition = content.length();
            }
            /*

            // 🔧 关键修复4：确保endPosition不小于当前分段起始位置
            if (endPosition < startPosition) {
                log.warn("Segment {} end position {} is before start position {}, adjusting to start position",
                        i, endPosition, startPosition);
                endPosition = startPosition;
            }*/

            // 进行精确分段
            if(endPosition < startPosition) {
                //endPosition = content.length();
                continue;
            }
            String segment = content.substring(startPosition, endPosition).trim();
            //if(!segment.startsWith(result.getSegments().get(i).getStartText())) {
            //    String tt = result.getSegments().get(i).getStartText();
            //}
            finalSegments.add(segment);
            /*
            if (startPosition < content.length() && startPosition < endPosition) {
                System.out.println("startPosition: " +startPosition + ",preEndPositionTest:" + preEndPositionTest + ",preEndPositionTestZoom:" + preEndPositionTestZoom);
                preEndPositionTestZoom = preEndPositionTestZoom + "(" + startPosition + "-" + endPosition + ")/n";
                String segment = content.substring(startPosition, endPosition).trim();
                preEndPositionTest = endPosition;
                if (!segment.isEmpty()) {
                    finalSegments.add(segment);
                    log.info("Segment {}: llmPos={}, matchPos={}, startPos={}, endPos={}, confidence={:.2f}, length={}, preview={}",
                            i + 1, info.llmPosition, info.matchedPosition, startPosition, endPosition,
                            info.matchConfidence, segment.length(),
                            segment.length() > 50 ? segment.substring(0, 50) + "..." : segment);

                    // 🔧 关键：记录当前段的结束位置，供下一段使用
                    previousEndPosition = endPosition;
                }
            } else {
                log.warn("Segment {} has invalid range: startPos={}, endPos={}, contentLength={}, skipping",
                        i, startPosition, endPosition, content.length());
            }*/
        }
        //System.out.println("preEndPositionTestZoom:" + preEndPositionTestZoom + ",total:" + content.length());
        return finalSegments;
    }

    /**
     * 🎯 验证分段质量
     */
    static boolean validateSegmentQuality(List<SegmentPositionInfo> segments) {
        if (segments.isEmpty()) return false;

        // 计算平均置信度
        double avgConfidence = segments.stream()
                .filter(s -> s.matchConfidence != null)
                .mapToDouble(s -> s.matchConfidence)
                .average()
                .orElse(0.0);

        log.info("Average match confidence: {:.2f}", avgConfidence);

        if (avgConfidence < CONFIDENCE_THRESHOLD) {
            log.warn("Low average confidence detected: {:.2f} < {}", avgConfidence, CONFIDENCE_THRESHOLD);
            return false;
        }

        // 检查位置单调性
        for (int i = 1; i < segments.size(); i++) {
            Integer prevPos = segments.get(i - 1).matchedPosition;
            Integer currPos = segments.get(i).matchedPosition;

            if (prevPos != null && currPos != null && currPos < prevPos) {
                log.warn("Non-monotonic positions detected: seg{}={}, seg{}={}",
                        i, prevPos, i + 1, currPos);
                return false;
            }
        }

        return true;
    }

    /**
     * 🔧 新增：预处理文本内容，统一格式
     * 目的：让LLM处理统一格式的文本，提高位置计算的准确性
     */
    static String preprocessContent(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        String processed = content;

        // 1. 统一换行符：将\r\n和\r都替换为\n
        processed = processed.replace("\r\n", "\n").replace("\r", "\n");

        // 2. 统一空格：将多个连续空格替换为单个空格
        processed = processed.replaceAll(" +", " ");

        // 3. 统一全角空格为半角空格
        processed = processed.replace("\u3000", " ");

        // 4. 移除特殊符号周围的多余空格（但保留必要的空格）
        processed = processed.replaceAll(" *([，。！？；：\u201c\u201d\u2018\u2019（）【】《》、]) *", "$1");

        // 5. 统一引号格式（可选，根据需要）
        processed = processed.replace("\u201c", "\"").replace("\u201d", "\"");
        processed = processed.replace("\u2018", "'").replace("\u2019", "'");

        // 6. 移除多余的连续换行（超过2个换行替换为2个）
        processed = processed.replaceAll("\n\n\n+", "\n\n");

        // 7. 清理每行的首尾空格
        String[] lines = processed.split("\n");
        StringBuilder cleanedLines = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                if (cleanedLines.length() > 0) {
                    cleanedLines.append("\n");
                }
                cleanedLines.append(line);
            }
        }

        String result = cleanedLines.toString();

        log.debug("Content preprocessing: {} -> {} chars", content.length(), result.length());

        return result;
    }

    /**
     * 🔧 新增：建立位置映射关系
     * 映射规则：处理后文本的位置 -> 原始文本中的对应位置
     *
     * 算法说明：
     * - 逐字符比较原始文本和处理后文本
     * - 对于匹配的字符，建立映射关系
     * - 对于被移除的字符（如多余空格），跳过不建立映射
     */
    /**
     * 🔧 新增：从原始列表中解析分段信息
     */
    static List<SegmentPositionInfo> parseSegmentsFromRawList(List<?> rawSegments) {
        List<SegmentPositionInfo> segmentPositions = new ArrayList<>();

        for (Object s : rawSegments) {
            if (s instanceof Map) {
                Map<String, Object> segmentMap = (Map<String, Object>) s;
                SegmentPositionInfo info = new SegmentPositionInfo();

                // 🎯 解析LLM返回的位置（作为模糊范围的中心点）
                if (segmentMap.containsKey("position")) {
                    try {
                        info.llmPosition = ((Number) segmentMap.get("position")).intValue();
                    } catch (Exception e) {
                        log.warn("Failed to parse position: {}", segmentMap.get("position"));
                    }
                }

                // 解析startText（用于验证）
                if (segmentMap.containsKey("startText")) {
                    info.startText = String.valueOf(segmentMap.get("startText"));
                }

                // 解析content（用于语义验证）
                if (segmentMap.containsKey("content")) {
                    info.content = String.valueOf(segmentMap.get("content"));
                }

                if (info.startText != null && !info.startText.trim().isEmpty()) {
                    segmentPositions.add(info);
                }
            }
        }

        return segmentPositions;
    }

    /**
     * 🔧 新增：解析多个对象拼接的格式 {...}{...}{...}
     * 这种格式通常出现在LLM直接输出多个JSON对象但没有包装在数组中的情况
     */
    static List<SegmentPositionInfo> parseSegmentsFromObjectConcatenation(String jsonResult) {
        List<SegmentPositionInfo> segmentPositions = new ArrayList<>();

        try {
            // 简单的分割策略：在每个"}{"之间插入逗号，然后整体包装为数组
            // 注意：这种简单策略可能在某些边界情况下失败，但能处理大多数常见情况

            // 首先清理markdown代码块标记
            String cleaned = jsonResult.replaceAll("```json", "").replaceAll("```", "").trim();

            // 尝试分割独立的JSON对象
            // 策略：寻找完整的对象边界 {...}
            int depth = 0;
            int objectStart = -1;

            for (int i = 0; i < cleaned.length(); i++) {
                char c = cleaned.charAt(i);

                if (c == '{') {
                    if (depth == 0) {
                        objectStart = i;
                    }
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && objectStart >= 0) {
                        // 找到一个完整的对象
                        String objectStr = cleaned.substring(objectStart, i + 1);
                        try {
                            JSONObject jsonObj = JSON.parseObject(objectStr);
                            if (jsonObj != null && jsonObj.containsKey("position")) {
                                SegmentPositionInfo info = new SegmentPositionInfo();

                                // 🎯 使用llmPosition字段
                                if (jsonObj.containsKey("position")) {
                                    info.llmPosition = jsonObj.getInteger("position");
                                }

                                if (jsonObj.containsKey("startText")) {
                                    info.startText = jsonObj.getString("startText");
                                }

                                if (jsonObj.containsKey("content")) {
                                    info.content = jsonObj.getString("content");
                                }

                                if (info.startText != null && !info.startText.trim().isEmpty()) {
                                    segmentPositions.add(info);
                                    log.debug("Parsed concatenated object {}: llmPos={}, startText='{}'",
                                            segmentPositions.size(),
                                            info.llmPosition,
                                            info.startText.length() > 20 ? info.startText.substring(0, 20) + "..." : info.startText);
                                }
                            }
                        } catch (Exception e) {
                            log.debug("Failed to parse individual object at position {}: {}", objectStart, e.getMessage());
                        }
                        objectStart = -1;
                    }
                }
            }

            log.info("Parsed {} segments from concatenated object format", segmentPositions.size());

        } catch (Exception e) {
            log.warn("Failed to parse concatenated object format: {}", e.getMessage());
        }

        return segmentPositions;
    }

    /**
     * 提取并清理JSON字符串
     */
    static String extractAndCleanJson(String rawResult) {
        if (rawResult == null || rawResult.trim().isEmpty()) {
            return "{\"segments\":[]}";
        }

        int startIndex = rawResult.indexOf('{');
        int endIndex = rawResult.lastIndexOf('}');

        if (startIndex == -1 || endIndex == -1) {
            log.warn("No valid JSON object found, trying array format");
            startIndex = rawResult.indexOf('[');
            endIndex = rawResult.lastIndexOf(']');
            if (startIndex != -1 && endIndex != -1) {
                String jsonContent = rawResult.substring(startIndex, endIndex + 1);
                return "{\"segments\":" + jsonContent + "}";
            }
            return "{\"segments\":[]}";
        }

        String jsonContent = rawResult.substring(startIndex, endIndex + 1);
        jsonContent = jsonContent.trim();
        jsonContent = jsonContent.replaceAll("```json", "").replaceAll("```", "");
        jsonContent = jsonContent.replaceAll("\\s+", " ").trim();

        return jsonContent;
    }

    /**
     * 解析模型返回的分段信息并验证位置准确性
     * 使用混合策略：模型返回位置 + 内容特征验证 + 位置修正
     */
    static List<String> parseSegmentsWithPositionValidation(String jsonResult, String originalContent) {
        try {
            JSONObject jsonObj = JSON.parseObject(jsonResult);
            if (!jsonObj.containsKey("segments")) {
                log.warn("JSON result missing 'segments' key");
                return new ArrayList<>();
            }

            Object segmentsObj = jsonObj.get("segments");
            List<SegmentInfo> segmentInfos = new ArrayList<>();

            if (segmentsObj instanceof List) {
                List<?> rawSegments = (List<?>) segmentsObj;

                for (Object s : rawSegments) {
                    if (s instanceof Map) {
                        Map<String, Object> segmentMap = (Map<String, Object>) s;
                        SegmentInfo info = new SegmentInfo();

                        // 解析position
                        if (segmentMap.containsKey("position")) {
                            try {
                                info.position = ((Number) segmentMap.get("position")).intValue();
                            } catch (Exception e) {
                                log.warn("Failed to parse position: {}", segmentMap.get("position"));
                            }
                        }

                        // 解析startText
                        if (segmentMap.containsKey("startText")) {
                            info.startText = String.valueOf(segmentMap.get("startText"));
                        }

                        // 解析content
                        if (segmentMap.containsKey("content")) {
                            info.content = String.valueOf(segmentMap.get("content"));
                        }

                        if (info.content != null && !info.content.trim().isEmpty()) {
                            segmentInfos.add(info);
                            log.debug("Parsed segment info: position={}, startText='{}', contentLength={}",
                                    info.position, info.startText, info.content.length());
                        }
                    }
                }
            }

            if (segmentInfos.isEmpty()) {
                log.warn("No valid segments found in LLM result");
                return new ArrayList<>();
            }

            // 验证和修正位置，然后根据修正后的位置进行分段
            return validatePositionsAndSegment(originalContent, segmentInfos);

        } catch (Exception e) {
            log.error("Failed to parse segments with position validation", e);
            return new ArrayList<>();
        }
    }

    /**
     * 验证和修正位置信息，然后进行精确分段
     */
    static List<String> validatePositionsAndSegment(String content, List<SegmentInfo> segmentInfos) {
        List<String> finalSegments = new ArrayList<>();

        for (int i = 0; i < segmentInfos.size(); i++) {
            SegmentInfo info = segmentInfos.get(i);

            // 确定最终使用的位置
            int finalPosition;

            if (info.position == null || info.position < 0) {
                // 如果没有位置信息，搜索startText
                log.warn("Segment {} missing position, searching for startText", i);
                finalPosition = findTextPosition(content, info.startText);
            } else {
                // 验证模型返回的位置是否准确
                int correctedPosition = validateAndCorrectPosition(content, info.position, info.startText);
                finalPosition = correctedPosition;
            }

            info.correctedPosition = finalPosition;

            // 计算分段的结束位置
            int endPosition;
            if (i < segmentInfos.size() - 1) {
                // 不是最后一个分段，使用下一个分段的起始位置
                SegmentInfo nextInfo = segmentInfos.get(i + 1);
                Integer nextPosition = (nextInfo.correctedPosition != null) ? nextInfo.correctedPosition : nextInfo.position;
                if (nextPosition != null && nextPosition > finalPosition) {
                    endPosition = nextPosition;
                } else {
                    endPosition = content.length();
                }
            } else {
                // 最后一个分段，到文档结尾
                endPosition = content.length();
            }

            // 进行精确分段
            if (finalPosition >= 0 && finalPosition < content.length() && finalPosition < endPosition) {
                String segment = content.substring(finalPosition, endPosition).trim();
                if (!segment.isEmpty()) {
                    finalSegments.add(segment);
                    log.info("Segment {}: position={}→{}, length={}, preview={}",
                            i + 1, info.position, finalPosition, segment.length(),
                            segment.length() > 50 ? segment.substring(0, 50) + "..." : segment);
                }
            }
        }

        return finalSegments;
    }

    /**
     * 验证和修正位置信息
     */
    static int validateAndCorrectPosition(String content, Integer modelPosition, String startText) {
        if (modelPosition == null || startText == null || startText.trim().isEmpty()) {
            return modelPosition != null ? modelPosition : 0;
        }

        // 检查模型位置是否有效
        if (modelPosition < 0 || modelPosition >= content.length()) {
            log.warn("Model position {} is out of range [0, {}], searching for startText",
                    modelPosition, content.length());
            return findTextPosition(content, startText);
        }

        // 验证模型位置是否与startText匹配
        if (modelPosition + startText.length() <= content.length()) {
            String textAtPosition = content.substring(modelPosition,
                    Math.min(modelPosition + startText.length(), content.length()));

            if (textAtPosition.contains(startText.trim())) {
                log.debug("Model position {} validated with startText '{}'", modelPosition, startText);
                return modelPosition;
            }
        }

        // 位置不匹配，搜索startText的真实位置
        int correctedPosition = findTextPosition(content, startText);
        if (correctedPosition >= 0 && correctedPosition != modelPosition) {
            log.info("Position corrected: {} → {} (deviation: {} chars)",
                    modelPosition, correctedPosition, Math.abs(correctedPosition - modelPosition));
        }

        return correctedPosition;
    }

    /**
     * 在原文中搜索文本特征，确定位置
     */
    static int findTextPosition(String content, String searchText) {
        if (searchText == null || searchText.trim().isEmpty()) {
            return 0;
        }

        String trimmedSearch = searchText.trim();

        // 首先尝试精确匹配
        int exactIndex = content.indexOf(trimmedSearch);
        if (exactIndex >= 0) {
            log.debug("Found exact match at position: {}", exactIndex);
            return exactIndex;
        }

        // 如果精确匹配失败，尝试模糊匹配（移除空格和换行）
        String normalizedSearch = trimmedSearch.replaceAll("\\s+", "");
        for (int i = 0; i < content.length() - 10; i++) {
            String window = content.substring(i, Math.min(i + normalizedSearch.length() + 50, content.length()));
            String normalizedWindow = window.replaceAll("\\s+", "");

            if (normalizedWindow.contains(normalizedSearch)) {
                log.info("Found fuzzy match at position: {} (searchText: '{}')", i, searchText);
                return i;
            }
        }

        log.warn("Text search failed for: '{}', using fallback position 0", searchText);
        return 0;
    }

    /**
     * 在原文中查找分段文本的起始位置
     * 使用特征文字（分段的前30-50个字符）在原文中定位
     * 多层级匹配策略：精确匹配 > 短特征匹配 > 模糊匹配 > 相似度匹配
     *
     * @param segmentText 分段文本内容
     * @param fullContent 原始完整文本
     * @return 分段在原文中的起始位置，未找到返回-1
     */
    static int findStartPositionInContent(String segmentText, String fullContent, String content) {
        /*
        if (segmentText == null || segmentText.trim().isEmpty()
                || fullContent == null || fullContent.isEmpty()) {
            return -1;
        }

        String trimmedSegment = segmentText.trim();

        // 提取特征文字：取分段的前30-50个字符
        int characteristicLen = Math.min(50, trimmedSegment.length());
        String characteristicText = trimmedSegment.substring(0, characteristicLen);

        // 1. 精确匹配
        int exactPos = fullContent.indexOf(characteristicText);
        if (exactPos >= 0) {
            log.debug("findStartPositionInContent: exact match at {}", exactPos);
            return exactPos;
        }

        // 2. 使用较短的特征文字重试（前20字符）
        if (characteristicLen > 20) {
            String shorterCharText = trimmedSegment.substring(0, 20);
            exactPos = fullContent.indexOf(shorterCharText);
            if (exactPos >= 0) {
                log.debug("findStartPositionInContent: shorter exact match at {}", exactPos);
                return exactPos;
            }
        }

        // 3. 模糊匹配：去除空白后搜索
        String normalizedChar = characteristicText.replaceAll("\\s+", "");
        for (int i = 0; i <= fullContent.length() - normalizedChar.length(); i++) {
            int windowEnd = Math.min(i + normalizedChar.length() + 50, fullContent.length());
            String window = fullContent.substring(i, windowEnd);
            String normalizedWindow = window.replaceAll("\\s+", "");
            if (normalizedWindow.contains(normalizedChar)) {
                log.debug("findStartPositionInContent: fuzzy match at {}", i);
                return i;
            }
        }

        // 4. 逐字符相似度匹配（滑动窗口）
        int windowSize = Math.min(30, trimmedSegment.length());
        String searchWindow = trimmedSegment.substring(0, windowSize);
        double bestSimilarity = 0.0;
        int bestPos = -1;

        for (int i = 0; i <= fullContent.length() - windowSize; i++) {
            String contentWindow = fullContent.substring(i, i + windowSize);
            double similarity = calculateSimilarity(searchWindow, contentWindow);
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestPos = i;
            }
        }

        // 置信度阈值：至少60%相似度
        if (bestSimilarity >= 0.6 && bestPos >= 0) {
            log.debug("findStartPositionInContent: similarity match at {} (score={})", bestPos, bestSimilarity);
            return bestPos;
        }

        log.warn("findStartPositionInContent: no match found for segment starting with: '{}'",
                characteristicText.substring(0, Math.min(20, characteristicText.length())));
        return -1;*/
        //String content = fullContent;
        if(segmentText.length() > 50) {
            segmentText.substring(0, 50);
        } else if(segmentText.length() > 30) {
            segmentText.substring(0, 30);
        } else if(segmentText.length() > 20) {
            segmentText.substring(0, 20);
        }

        String searchText = segmentText;

        int exactIndex = content.indexOf(searchText);
        if (exactIndex >= 0) {
            //String testStr = content.substring(exactIndex, exactIndex + searchText.length());
            String testStr = content.substring(exactIndex, exactIndex + searchText.length() > content.length()?content.length():exactIndex + searchText.length());
            //System.out.println("s1:" + searchText);
            //System.out.println("s2:" + testStr);
            if(!testStr.equals(searchText)) {
                //System.out.println("s3:" + testStr);
            }
            return exactIndex;
        }

        String tt_str = new String(searchText.replace("\n", "\n\r"));
        exactIndex = content.indexOf(tt_str);
        if (exactIndex >= 0) {
            //String testStr = content.substring(exactIndex, exactIndex + searchText.length());
            String testStr = content.substring(exactIndex, exactIndex + searchText.length() > content.length()?content.length():exactIndex + searchText.length());
            //System.out.println("s1:" + searchText);
            //System.out.println("s2:" + testStr);
            if(!testStr.equals(searchText)) {
                //System.out.println("s3:" + testStr);
            }
            return exactIndex;
        }

        String tt_str2 = new String(searchText.replace("\n", "\n\n"));
        exactIndex = content.indexOf(tt_str2);
        if (exactIndex >= 0) {
            //String testStr = content.substring(exactIndex, exactIndex + searchText.length());
            String testStr = content.substring(exactIndex, exactIndex + searchText.length() > content.length()?content.length():exactIndex + searchText.length());
            //System.out.println("s1:" + searchText);
            //System.out.println("s2:" + testStr);
            if(!testStr.equals(searchText)) {
                //System.out.println("s3:" + testStr);
            }
            return exactIndex;
        }

        String tt_str3 = searchText.substring(0, searchText.length()/2);
        exactIndex = content.indexOf(tt_str3);
        if (exactIndex >= 0) {
            //String testStr = content.substring(exactIndex, exactIndex + searchText.length());
            String testStr = content.substring(exactIndex, exactIndex + searchText.length() > content.length()?content.length():exactIndex + searchText.length());
            //System.out.println("s1:" + searchText);
            //System.out.println("s2:" + testStr);
            if(!testStr.equals(searchText)) {
                //System.out.println("s3:" + testStr);
            }
            return exactIndex;
        }

        String tt_str4 = searchText.substring(0, tt_str3.length()/2);
        if(tt_str4.length() > 20) {
            //String tt_str4 = searchText.substring(0, tt_str3.length()/2);
            exactIndex = content.indexOf(tt_str4);
            if (exactIndex >= 0) {
                //String testStr = content.substring(exactIndex, exactIndex + searchText.length());
                String testStr = content.substring(exactIndex, exactIndex + searchText.length() > content.length()?content.length():exactIndex + searchText.length());
                //System.out.println("s1:" + searchText);
                //System.out.println("s2:" + testStr);
                if(!testStr.equals(searchText)) {
                    //System.out.println("s3:" + testStr);
                }
                return exactIndex;
            }

            exactIndex = content.indexOf(tt_str4.replace("\n", "\n\r"));
            if (exactIndex >= 0) {
                //String testStr = content.substring(exactIndex, exactIndex + searchText.length());
                String testStr = content.substring(exactIndex, exactIndex + searchText.length() > content.length()?content.length():exactIndex + searchText.length());
                //System.out.println("s1:" + searchText);
                //System.out.println("s2:" + testStr);
                if(!testStr.equals(searchText)) {
                    //System.out.println("s3:" + testStr);
                }
                return exactIndex;
            }


        }

        String tt_str5 = searchText.substring(0, tt_str4.length()/2);
        if(tt_str5.length() > 20) {
            exactIndex = content.indexOf(tt_str5);
            if (exactIndex >= 0) {
                //String testStr = content.substring(exactIndex, exactIndex + searchText.length());
                String testStr = content.substring(exactIndex, exactIndex + searchText.length() > content.length()?content.length():exactIndex + searchText.length());
                //System.out.println("s1:" + searchText);
                //System.out.println("s2:" + testStr);
                if(!testStr.equals(searchText)) {
                    //System.out.println("s3:" + testStr);
                }
                return exactIndex;
            }
            //.replace("\n", "\n\r")
            exactIndex = content.indexOf(tt_str5.replace("\n", "\n\r"));
            if (exactIndex >= 0) {
                //String testStr = content.substring(exactIndex, exactIndex + searchText.length());
                String testStr = content.substring(exactIndex, exactIndex + searchText.length() > content.length()?content.length():exactIndex + searchText.length());
                //System.out.println("s1:" + searchText);
                //System.out.println("s2:" + testStr);
                if(!testStr.equals(searchText)) {
                    //System.out.println("s3:" + testStr);
                }
                return exactIndex;
            }
        }

        if(tt_str5.length() > 15) {
            tt_str5 = tt_str5.substring(0, 15);
        }

        for(int i=0; i<tt_str5.length(); i++) {
            String keyword = new String(tt_str5.substring(0, i+1) + "\n" + tt_str5.substring(i+1, tt_str5.length()));
            if(content.indexOf(keyword) >= 0) {
                exactIndex = content.indexOf(keyword);
                return exactIndex;
            }
        }

        // 2. Fallback: handle line ending mismatch (content has \r\n, searchText has \n)
        //    Build a normalized content copy and a position mapping back to original
        if(content.indexOf(tt_str5) >= 0) {
            exactIndex = content.indexOf(tt_str5);
            return exactIndex;
        }

        return -1;
    }

    /**
     * 🎯 将DTO对象列表转换为分段字符串列表
     * 将List<SmartParsingSegments>转换为List<String>
     */
    static List<String> parseSegmentsFromDtoList(List<SmartParsingSegments> segmentList, String originalContent) {
        List<String> segments = new ArrayList<>();

        if (segmentList == null || segmentList.isEmpty()) {
            return segments;
        }

        // 按位置排序
        segmentList.sort((a, b) -> {
            if (a.getPosition() == null && b.getPosition() == null) return 0;
            if (a.getPosition() == null) return 1;
            if (b.getPosition() == null) return -1;
            return Integer.compare(a.getPosition(), b.getPosition());
        });

        for (int i = 0; i < segmentList.size(); i++) {
            SmartParsingSegments seg = segmentList.get(i);

            if (seg.getPosition() == null || seg.getPosition() < 0) {
                log.warn("Skipping segment with invalid position: {}", seg);
                continue;
            }

            // 计算结束位置
            int endPosition;
            if (i < segmentList.size() - 1) {
                SmartParsingSegments nextSeg = segmentList.get(i + 1);
                endPosition = nextSeg.getPosition() != null ? nextSeg.getPosition() : originalContent.length();
            } else {
                endPosition = originalContent.length();
            }

            // 边界检查
            if (endPosition > originalContent.length()) {
                endPosition = originalContent.length();
            }

            if (seg.getPosition() < endPosition && seg.getPosition() < originalContent.length()) {
                String segment = originalContent.substring(seg.getPosition(), endPosition).trim();
                if (!segment.isEmpty()) {
                    segments.add(segment);
                    log.info("Segment {}: position={}, length={}, preview={}",
                            i + 1, seg.getPosition(), segment.length(),
                            segment.length() > 50 ? segment.substring(0, 50) + "..." : segment);
                }
            }
        }

        return segments;
    }

    /**
     * 🎯 新增：模糊范围定义
     */
    private static class FuzzyRange {
        int centerPosition;  // LLM返回的中心位置
        int startIndex;      // 搜索范围起始位置
        int endIndex;        // 搜索范围结束位置
        int rangeSize;       // 范围大小

        FuzzyRange(int centerPosition, int startIndex, int endIndex, int rangeSize) {
            this.centerPosition = centerPosition;
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.rangeSize = rangeSize;
        }

        @Override
        public String toString() {
            return String.format("FuzzyRange{center=%d, range=[%d,%d], size=%d}",
                    centerPosition, startIndex, endIndex, rangeSize);
        }
    }

    /**
     * 🎯 新增：匹配结果及置信度
     */
    private static class MatchResult {
        int position;           // 找到的位置
        double confidenceScore; // 匹配置信度 (0.0-1.0)
        String matchedText;     // 实际匹配的文本
        String matchStrategy;   // 使用的匹配策略

        MatchResult(int position, double confidenceScore, String matchedText, String matchStrategy) {
            this.position = position;
            this.confidenceScore = confidenceScore;
            this.matchedText = matchedText;
            this.matchStrategy = matchStrategy;
        }

        @Override
        public String toString() {
            return String.format("MatchResult{pos=%d, confidence=%.2f, strategy=%s}",
                    position, confidenceScore, matchStrategy);
        }
    }

    /**
     * 🎯 增强：分段位置信息数据结构（用于模糊匹配方案）
     */
    private static class SegmentPositionInfo {
        Integer llmPosition;         // LLM返回的原始位置
        Integer matchedPosition;     // 模糊匹配找到的位置
        String startText;            // 用于搜索的文本
        String content;              // 完整分段内容
        Double matchConfidence;      // 匹配置信度
        FuzzyRange searchRange;      // 使用的搜索范围
        String matchedText;          // 实际匹配到的文本
        String matchStrategy;        // 匹配策略

        @Override
        public String toString() {
            return String.format("SegmentPosInfo{llmPos=%d, matchPos=%d, confidence=%.2f, strategy=%s}",
                    llmPosition, matchedPosition, matchConfidence, matchStrategy);
        }
    }

    /**
     * 分段信息数据结构（保留用于兼容性）
     */
    private static class SegmentInfo {
        Integer position;        // 模型返回的位置
        String startText;       // 起始文本特征
        String content;         // 完整分段内容
        Integer correctedPosition; // 修正后的位置
    }




    /**
     * 将SegmentPositionInfo列表按matchedPosition从小到大排序
     */
    static List<SegmentPositionInfo> sortSegmentPositionInfo(List<SegmentPositionInfo> segmentPositions) {
        List<SegmentPositionInfo> sorted = new ArrayList<>(segmentPositions);
        sorted.sort((a, b) -> {
            if (a.matchedPosition == null && b.matchedPosition == null) return 0;
            if (a.matchedPosition == null) return 1;
            if (b.matchedPosition == null) return -1;
            return Integer.compare(a.matchedPosition, b.matchedPosition);
        });
        return sorted;
    }
}
