package com.xspaceagi.knowledge;

import com.xspaceagi.knowledge.domain.docparser.SegmentPositionMatcher;
import com.xspaceagi.knowledge.domain.docparser.SegmentTextSupport;
import com.xspaceagi.knowledge.domain.docparser.SmartParsingAIExtService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 递归智能分段测试
 * 测试 buildBigSegments 在 10000-100000 字区间的新递归分段逻辑
 */
@Slf4j
public class SmartParsingRecursiveSegmentTest {

    private static SmartParsingAIExtService service;
    private static Method coarseSplitMethod;

    @BeforeAll
    static void setup() throws Exception {
        service = new SmartParsingAIExtService();
        coarseSplitMethod = SegmentTextSupport.class.getDeclaredMethod(
                "coarseSplitBySentence", String.class, int.class);
        coarseSplitMethod.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeCoarseSplit(String text, int targetSize) throws Exception {
        return (List<String>) coarseSplitMethod.invoke(service, text, targetSize);
    }

    private String buildNoPunctuationContent(int targetChars) {
        StringBuilder sb = new StringBuilder();
        String[] topics = {
            "人工智能是计算机科学的一个重要分支它研究如何让机器模拟人类的智能行为",
            "机器学习是人工智能的核心技术之一它使计算机能够从数据中自动学习和改进",
            "深度学习是机器学习的一个子领域它使用多层神经网络来处理复杂的模式识别",
            "自然语言处理是人工智能中研究计算机理解和生成人类语言的技术",
            "计算机视觉是让计算机从图像或视频中提取信息并理解视觉世界的技术",
            "强化学习是机器学习的一种方法它通过试错和奖励机制来学习最优策略",
            "知识图谱是一种结构化的知识表示方法它用图的形式来描述实体和关系",
            "语音识别技术将人类的语音信号转换为文本或命令在许多应用场景中广泛使用",
            "推荐系统根据用户的历史行为和偏好来预测和推荐用户可能感兴趣的内容",
            "生成对抗网络是一种深度学习模型它通过两个神经网络的对抗训练来生成数据"
        };
        int i = 0;
        while (sb.length() < targetChars) {
            sb.append(topics[i % topics.length]);
            i++;
        }
        return sb.toString();
    }

    // ==================== coarseSplitBySentence tests ====================

    @Test
    void testCoarseSplit_singleChunk_whenShortContent() throws Exception {
        String content = buildNoPunctuationContent(5000);
        List<String> chunks = invokeCoarseSplit(content, 6000);
        assertEquals(1, chunks.size());
        assertEquals(content, chunks.get(0));
    }

    @Test
    void testCoarseSplit_multipleChunks_whenLongContent() throws Exception {
        String content = buildNoPunctuationContent(15000);
        List<String> chunks = invokeCoarseSplit(content, 6000);
        assertTrue(chunks.size() > 1);
        assertNoTextLoss(content, chunks);
    }

    @Test
    void testCoarseSplit_noPunctuation_12000chars() throws Exception {
        String content = buildNoPunctuationContent(12000);
        List<String> chunks = invokeCoarseSplit(content, 6000);
        assertTrue(chunks.size() >= 2);
        log.info("12000 chars no-punctuation content:");
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            log.info("  Chunk {}: {} chars", i, chunk.length());
        }
        assertNoTextLoss(content, chunks);
    }

    // ==================== findTextPosition tests (existing method) ====================

    @Test
    void testFindTextPosition_exactMatch() throws Exception {
        String content = "前缀文字人工智能是计算机科学的重要分支后续文字";
        String segmentText = "人工智能是计算机科学的重要分支";
        Method m = SegmentPositionMatcher.class.getDeclaredMethod("findTextPosition", String.class, String.class);
        m.setAccessible(true);
        int pos = (int) m.invoke(service, content, segmentText);
        assertEquals(4, pos);
    }

    @Test
    void testFindTextPosition_notFound() throws Exception {
        String content = "这是完全不同的内容";
        String segmentText = "人工智能是计算机科学";
        Method m = SegmentPositionMatcher.class.getDeclaredMethod("findTextPosition", String.class, String.class);
        m.setAccessible(true);
        int pos = (int) m.invoke(service, content, segmentText);
        assertEquals(0, pos);
    }

    // ==================== findStartPositionInContent tests ====================

    @Test
    void testFindStartPositionInContent_basicCase() throws Exception {
        Method m = SegmentPositionMatcher.class.getDeclaredMethod("findStartPositionInContent", String.class, String.class);
        m.setAccessible(true);
        String content = "这是前面的内容人工智能是计算机科学的一个重要分支后续还有更多内容";
        String segmentText = "人工智能是计算机科学的一个重要分支后续还有";
        int pos = (int) m.invoke(service, segmentText, content);
        assertEquals(7, pos);
    }

    @Test
    void testFindStartPositionInContent_withLongContent() throws Exception {
        Method m = SegmentPositionMatcher.class.getDeclaredMethod("findStartPositionInContent", String.class, String.class);
        m.setAccessible(true);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("这是第").append(i).append("段内容用来填充文本长度");
        }
        String content = sb.toString();
        assertTrue(content.length() > 5000, "Need content > 5000 chars, got " + content.length());
        int targetPos = 5000;
        String segmentText = content.substring(targetPos, targetPos + 200);
        int pos = (int) m.invoke(service, segmentText, content);
        assertEquals(targetPos, pos);
    }

    @Test
    void testFindStartPositionInContent_notFound() throws Exception {
        Method m = SegmentPositionMatcher.class.getDeclaredMethod("findStartPositionInContent", String.class, String.class);
        m.setAccessible(true);
        String content = "完全不同的内容没有任何匹配点在这段文字里面";
        String segmentText = "人工智能是计算机科学的一个重要分支研究领域";
        int pos = (int) m.invoke(service, segmentText, content);
        assertEquals(-1, pos);
    }

    @Test
    void testFindStartPositionInContent_withExactStartText() throws Exception {
        Method m = SegmentPositionMatcher.class.getDeclaredMethod("findStartPositionInContent", String.class, String.class);
        m.setAccessible(true);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("这是第").append(i).append("段填充内容");
        }
        String content = sb.toString();
        int targetPos = 500;
        String segment = content.substring(targetPos, targetPos + 200);
        int pos = (int) m.invoke(service, segment, content);
        assertEquals(targetPos, pos);
    }

    // ==================== Recursive logic tests ====================

    @Test
    void testRecursiveLogic_baseCase_singleCoarseChunk() throws Exception {
        String content = buildNoPunctuationContent(5000);
        List<String> coarseChunks = invokeCoarseSplit(content, 6000);
        assertEquals(1, coarseChunks.size());
    }

    @Test
    void testRecursiveLogic_recursiveCase_multipleCoarseChunks() throws Exception {
        String content = buildNoPunctuationContent(15000);
        List<String> coarseChunks = invokeCoarseSplit(content, 6000);
        assertTrue(coarseChunks.size() >= 2);
        assertTrue(content.startsWith(coarseChunks.get(0)));
        assertNoTextLoss(content, coarseChunks);
    }

    @Test
    void testRecursiveLogic_positionCalculation() throws Exception {
        String fullContent = buildNoPunctuationContent(15000);
        List<String> coarseChunks = invokeCoarseSplit(fullContent, 6000);
        String firstChunk = coarseChunks.get(0);
        String lastSegmentText = firstChunk.substring(firstChunk.length() - 1000);

        Method m = SegmentPositionMatcher.class.getDeclaredMethod("findStartPositionInContent", String.class, String.class);
        m.setAccessible(true);
        int position = (int) m.invoke(service, lastSegmentText, fullContent);
        assertTrue(position > 0);
        assertTrue(position < fullContent.length());

        String remainingContent = fullContent.substring(position);
        assertTrue(remainingContent.length() > 0);
        assertTrue(remainingContent.length() < fullContent.length());
        log.info("Position: {}, remaining: {} chars from {} total", position, remainingContent.length(), fullContent.length());
    }

    @Test
    void testRecursiveLogic_fullSimulation_noPunctuation() throws Exception {
        String fullContent = buildNoPunctuationContent(20000);
        log.info("Full simulation: {} chars", fullContent.length());
        Method m = SegmentPositionMatcher.class.getDeclaredMethod("findStartPositionInContent", String.class, String.class);
        m.setAccessible(true);

        List<String> coarse1 = invokeCoarseSplit(fullContent, 6000);
        log.info("Round 1: {} coarse chunks", coarse1.size());
        if (coarse1.size() > 1) {
            String firstChunk1 = coarse1.get(0);
            String removedSegment = firstChunk1.substring(firstChunk1.length() - 500);
            int pos1 = (int) m.invoke(service, removedSegment, fullContent);
            log.info("Round 1: removed segment at position {}", pos1);
            assertTrue(pos1 > 0);

            String remaining2 = fullContent.substring(pos1);
            List<String> coarse2 = invokeCoarseSplit(remaining2, 6000);
            log.info("Round 2: {} coarse chunks", coarse2.size());
        }
    }

    @Test
    void testRecursiveLogic_convergence() throws Exception {
        // 使用有区分度的内容（含序号）确保特征文字唯一匹配
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("第").append(i).append("段：人工智能技术正在深刻改变着人类社会的发展方向和生活方式");
        }
        String content = sb.toString();
        assertTrue(content.length() > 20000, "Need enough content");
        
        Method findStartMethod = SegmentPositionMatcher.class.getDeclaredMethod("findStartPositionInContent", String.class, String.class);
        findStartMethod.setAccessible(true);

        int round = 0;
        String remaining = content;
        while (round < 50) {
            round++;
            List<String> coarse = invokeCoarseSplit(remaining, 6000);
            if (coarse.size() == 1) {
                log.info("Converged at round {} with {} chars remaining", round, remaining.length());
                break;
            }
            String firstChunk = coarse.get(0);
            String lastSegment = firstChunk.substring((int) (firstChunk.length() * 0.7));
            int pos = (int) findStartMethod.invoke(service, lastSegment, remaining);
            if (pos <= 0) {
                log.warn("Position finding failed at round {}", round);
                break;
            }
            remaining = remaining.substring(pos);
            log.info("Round {}: {} coarse chunks, remaining {} chars", round, coarse.size(), remaining.length());
        }
        assertTrue(round < 50, "Should converge within 50 rounds, took " + round);
    }

    // ==================== Range tests ====================

    @Test
    void testCoarseSplit_10000_to_100000_range() throws Exception {
        int[] testSizes = {10001, 15000, 30000};
        for (int size : testSizes) {
            String content = buildNoPunctuationContent(size);
            List<String> chunks = invokeCoarseSplit(content, 6000);
            assertNoTextLoss(content, chunks);
            log.info("Size {}: {} chunks", size, chunks.size());
        }
    }

    private void assertNoTextLoss(String original, List<String> segments) {
        StringBuilder reconstructed = new StringBuilder();
        for (String seg : segments) reconstructed.append(seg);
        assertEquals(original, reconstructed.toString(), "No text loss");
    }

    // ==================== tryExactMatch line ending tests ====================

    @Test
    void testTryExactMatch_rnInContent_nInSearch() throws Exception {
        Method m = SegmentPositionMatcher.class.getDeclaredMethod(
                "tryExactMatch", String.class, String.class,
                Class.forName("com.xspaceagi.knowledge.domain.docparser.SegmentPositionMatcher$FuzzyRange"));
        m.setAccessible(true);

        String content = "人工智能技术综述\r\n\r\n一 人工智能概述\r\n\r\n人工智能 Artificial Intelligence 简称AI";
        String searchText = "人工智能技术综述\n\n一 人工智能概述\n\n人工智能 Artificial Intelligence";

        Object range = buildFuzzyRange(content.length(), 0, content.length());
        Object result = m.invoke(service, content, searchText, range);

        java.lang.reflect.Field posField = result.getClass().getDeclaredField("position");
        posField.setAccessible(true);
        java.lang.reflect.Field confidenceField = result.getClass().getDeclaredField("confidenceScore");
        confidenceField.setAccessible(true);
        java.lang.reflect.Field strategyField = result.getClass().getDeclaredField("matchStrategy");
        strategyField.setAccessible(true);

        int position = posField.getInt(result);
        double confidence = confidenceField.getDouble(result);
        String strategy = (String) strategyField.get(result);

        log.info("testTryExactMatch_rnInContent_nInSearch: pos={}, confidence={}, strategy={}", position, confidence, strategy);

        assertEquals(0, position, "Should find at position 0");
        assertTrue(confidence >= 0.9, "Should have high confidence, got " + confidence);
        assertTrue(strategy.contains("exact"), "Strategy should be exact-like, got " + strategy);
    }

    @Test
    void testTryExactMatch_rnInContent_nInSearch_midContent() throws Exception {
        Method m = SegmentPositionMatcher.class.getDeclaredMethod(
                "tryExactMatch", String.class, String.class,
                Class.forName("com.xspaceagi.knowledge.domain.docparser.SegmentPositionMatcher$FuzzyRange"));
        m.setAccessible(true);

        String content = "前面的内容\r\n\r\n中间部分的开始文字\r\n\r\n后面还有更多";
        String searchText = "中间部分的开始文字\n\n后面还有";

        Object range = buildFuzzyRange(content.length(), 0, content.length());
        Object result = m.invoke(service, content, searchText, range);

        java.lang.reflect.Field posField = result.getClass().getDeclaredField("position");
        posField.setAccessible(true);
        java.lang.reflect.Field confidenceField = result.getClass().getDeclaredField("confidenceScore");
        confidenceField.setAccessible(true);

        int position = posField.getInt(result);
        double confidence = confidenceField.getDouble(result);

        log.info("testTryExactMatch midContent: pos={}, confidence={}", position, confidence);

        // "前面的内容\r\n\r\n" = "前面的内容" (4) + "\r\n\r\n" (4) = 8
        assertEquals(9, position, "Should find at position 9");
        assertTrue(confidence >= 0.9, "Should have high confidence");
    }

    @Test
    void testTryExactMatch_exactMatch_stillWorks() throws Exception {
        Method m = SegmentPositionMatcher.class.getDeclaredMethod(
                "tryExactMatch", String.class, String.class,
                Class.forName("com.xspaceagi.knowledge.domain.docparser.SegmentPositionMatcher$FuzzyRange"));
        m.setAccessible(true);

        String content = "人工智能技术综述\r\n\r\n一 人工智能概述";
        String searchText = "人工智能技术综述\r\n\r\n一 人工智能概述";

        Object range = buildFuzzyRange(content.length(), 0, content.length());
        Object result = m.invoke(service, content, searchText, range);

        java.lang.reflect.Field confidenceField = result.getClass().getDeclaredField("confidenceScore");
        confidenceField.setAccessible(true);
        java.lang.reflect.Field strategyField = result.getClass().getDeclaredField("matchStrategy");
        strategyField.setAccessible(true);

        double confidence = confidenceField.getDouble(result);
        String strategy = (String) strategyField.get(result);

        assertEquals(1.0, confidence, 0.01, "Exact match should be confidence 1.0");
        assertEquals("exact", strategy, "Should be exact strategy");
    }

    @Test
    void testTryExactMatch_noMatch_stillFails() throws Exception {
        Method m = SegmentPositionMatcher.class.getDeclaredMethod(
                "tryExactMatch", String.class, String.class,
                Class.forName("com.xspaceagi.knowledge.domain.docparser.SegmentPositionMatcher$FuzzyRange"));
        m.setAccessible(true);

        String content = "完全不相关的内容\r\n\r\n没有任何匹配的文字";
        String searchText = "人工智能技术综述\n\n一 人工智能概述";

        Object range = buildFuzzyRange(content.length(), 0, content.length());
        Object result = m.invoke(service, content, searchText, range);

        java.lang.reflect.Field confidenceField = result.getClass().getDeclaredField("confidenceScore");
        confidenceField.setAccessible(true);

        double confidence = confidenceField.getDouble(result);
        assertTrue(confidence < 0.5, "No match should have low confidence, got " + confidence);
    }

    private Object buildFuzzyRange(int contentLength, int startIndex, int endIndex) throws Exception {
        Class<?> fuzzyRangeClass = Class.forName("com.xspaceagi.knowledge.domain.docparser.SegmentPositionMatcher$FuzzyRange");
        java.lang.reflect.Constructor<?> constructor = fuzzyRangeClass.getDeclaredConstructor(int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(startIndex, startIndex, endIndex, endIndex - startIndex);
    }

}
