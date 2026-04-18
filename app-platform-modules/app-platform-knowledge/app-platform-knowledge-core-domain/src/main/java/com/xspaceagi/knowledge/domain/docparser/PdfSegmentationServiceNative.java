package com.xspaceagi.knowledge.domain.docparser;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Pattern;

/**
 * PDF 精准分段服务 - 原生版本（不依赖数据库）
 *
 * 核心思路：
 * 1. 使用 PDFBox 提取文本块
 * 2. 先识别所有章节标题位置
 * 3. 两个标题之间的所有内容归入同一分段
 *
 * 返回结果：List<String> 分段文本列表
 */
@Service
@Slf4j
public class PdfSegmentationServiceNative {

    // ==================== 正则表达式模式库 ====================

    private static final Pattern H1_CHAPTER = Pattern.compile("^第[一二三四五六七八九十百千万]+[章部分节][\\s　：:].{2,}$");
    private static final Pattern H1_CHAPTER_NUM = Pattern.compile("^第\\d+[章部分节][\\s　：:].{2,}$");
    private static final Pattern H1_CHINESE_NUM = Pattern.compile("^[一二三四五六七八九十]+[、][\\s　]*[\\u4e00-\\u9fa5][^，。！？；：]*$");
    private static final Pattern H2_NUMBER = Pattern.compile("^\\d+\\.\\d+[\\s　]+[^\\d\\s].{3,}$");
    private static final Pattern H2_CHINESE_PAREN = Pattern.compile("^[（(][一二三四五六七八九十]+[)）][\\s　]*[^\\d\\s].{2,}$");
    private static final Pattern H3_NUMBER = Pattern.compile("^\\d+\\.\\d+\\.\\d+[\\s　]+[^\\d\\s].{3,}$");
    private static final Pattern REFERENCES_TITLE = Pattern.compile("^参考文献[：:·]?$|^References[:：]?$|^REFERENCES$");
    private static final Pattern PAGE_NUM = Pattern.compile("^-?\\d{1,3}-?$");
    private static final Pattern HEADER_FOOTER = Pattern.compile("^[DOI|ISSN|CN].*$|^\\d{4}[-/]\\d{2}[-/]\\d{2}$|^第\\d+页$");

    // ==================== 块类型常量 ====================
    private static final int TYPE_TITLE = 0;
    private static final int TYPE_H1 = 1;
    private static final int TYPE_H2 = 2;
    private static final int TYPE_H3 = 3;
    private static final int TYPE_BODY = 4;
    private static final int TYPE_REFERENCES = 5;
    private static final int TYPE_NOISE = 6;

    // ==================== 主入口 ====================

    /**
     * 从 URL 解析 PDF
     */
    public List<String> parseFromUrl(String pdfUrl) throws Exception {
        log.info("Download PDF: {}", pdfUrl);
        Path tempFile = Files.createTempFile("pdf_", ".pdf");
        try (InputStream in = new URL(pdfUrl).openStream()) {
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            return parsePdf(tempFile.toFile());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * 解析 PDF 文件
     */
    public List<String> parsePdf(File pdfFile) throws Exception {
        log.info("Parse PDF: {}", pdfFile.getName());

        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            List<Object[]> blocks = extractBlocks(doc);
            log.info("Extracted {} text blocks", blocks.size());
            if (blocks.isEmpty()) return new ArrayList<>();

            float bodyFontSize = analyzeBodyFontSize(blocks);
            log.info("Body font size: {}", bodyFontSize);

            identifyHeadings(blocks, bodyFontSize);
            return buildSegments(blocks);
        }
    }

    // ==================== Step 1: 文本块提取 ====================

    /**
     * 提取文本块
     * 返回: List<Object[]> - 每个元素为 [text, fontSize, page, type]
     */
    private List<Object[]> extractBlocks(PDDocument doc) throws IOException {
        List<Object[]> allBlocks = new ArrayList<>();

        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            final int pageIndex = i;
            PDPage page = doc.getPage(pageIndex);
            PDRectangle box = page.getMediaBox();

            log.info("Page {}: extract text blocks", pageIndex + 1);

            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) throws IOException {
                    if (positions.isEmpty()) return;

                    text = text.trim();
                    if (text.isEmpty()) return;

                    // 计算平均字号
                    float totalFontSize = 0;
                    int validCount = 0;
                    for (TextPosition p : positions) {
                        float fs = p.getFontSize();
                        if (fs > 1.0f) {
                            totalFontSize += fs;
                            validCount++;
                        } else {
                            totalFontSize += p.getHeight();
                            validCount++;
                        }
                    }
                    float fontSize = validCount > 0 ? totalFontSize / validCount : 12;

                    // [text, fontSize, page, type]
                    allBlocks.add(new Object[]{text, fontSize, pageIndex, TYPE_BODY});
                }
            };

            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            stripper.writeText(doc, new StringWriter());
        }

        return allBlocks;
    }

    // ==================== Step 2: 文档特征分析 ====================

    private float analyzeBodyFontSize(List<Object[]> blocks) {
        Map<Float, Integer> fontSizeFreq = new HashMap<>();
        for (Object[] b : blocks) {
            float fs = Math.round((float) b[1]);
            //if (fs >= 8 && fs <= 24) {
            if (fs >= 8 && fs <= 24) {
                fontSizeFreq.merge(fs, 1, Integer::sum);
            }
        }
        return fontSizeFreq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(12f);
    }

    // ==================== Step 3: 识别章节标题 ====================

    private void identifyHeadings(List<Object[]> blocks, float bodyFontSize) {
        boolean inReferences = false;
        boolean foundTitle = false;

        for (Object[] block : blocks) {
            String text = (String) block[0];
            float fontSize = (float) block[1];
            float fontRatio = fontSize / bodyFontSize;

            // 1. 噪声过滤
            if (PAGE_NUM.matcher(text).matches() || HEADER_FOOTER.matcher(text).matches()) {
                block[3] = TYPE_NOISE;
                continue;
            }

            // 2. 参考文献区域
            if (REFERENCES_TITLE.matcher(text).matches()) {
                block[3] = TYPE_REFERENCES;
                inReferences = true;
                log.info("Detected reference title: {}", text);
                continue;
            }

            if (inReferences) {
                block[3] = TYPE_BODY;
                continue;
            }


            // 3. 文档标题
            //if (!foundTitle && fontRatio >= 1.15f && text.length() >= 5 && text.length() <= 100) {
            if (fontRatio >= 1.15f ) {
                block[3] = TYPE_TITLE;
                foundTitle = true;
                log.info("Detected doc title: {}", text);
                continue;
            }

            // 4. 一级标题
            if (H1_CHAPTER.matcher(text).matches() || H1_CHAPTER_NUM.matcher(text).matches() ||
                    H1_CHINESE_NUM.matcher(text).matches() || isH1Heading(text)) {
                block[3] = TYPE_H1;
                log.info("Detected H1: {}", text);
                continue;
            }

            // 5. 二级标题
            if (H2_NUMBER.matcher(text).matches() || H2_CHINESE_PAREN.matcher(text).matches()) {
                block[3] = TYPE_H2;
                log.info("Detected H2: {}", text);
                continue;
            }

            // 6. 三级标题
            if (H3_NUMBER.matcher(text).matches()) {
                block[3] = TYPE_H3;
                log.info("Detected H3: {}", text);
            }
        }
    }

    private boolean isH1Heading(String text) {
        if (!text.matches("^\\d+[\\s　]+[^\\d\\s].{2,}$")) return false;
        if (text.length() > 50) return false;
        if (text.startsWith("[")) return false;

        String[] tableKeywords = {"指标体系", "描述性统计", "事件分析", "机制分析", "异质性分析",
                "新质生产力指标", "人工智能政策试点"};
        for (String keyword : tableKeywords) {
            if (text.contains(keyword)) return false;
        }

        if (text.matches("^\\d+[\\s　]{2,}[\\u4e00-\\u9fa5]{2,4}$")) return false;

        return true;
    }

    // ==================== Step 4: 构建分段 ====================

    private List<String> buildSegments(List<Object[]> blocks) {
        List<String> segments = new ArrayList<>();

        int i = 0;
        while (i < blocks.size()) {
            Object[] block = blocks.get(i);
            int type = (int) block[3];

            // 跳过噪声
            if (type == TYPE_NOISE) {
                i++;
                continue;
            }

            // 标题类型（TITLE, H1, REFERENCES）单独分段
            if (type == TYPE_TITLE || type == TYPE_H1 || type == TYPE_REFERENCES) {
                segments.add((String) block[0]);
                i++;

                // 收集后续内容
                StringBuilder content = new StringBuilder();
                while (i < blocks.size()) {
                    Object[] next = blocks.get(i);
                    int nextType = (int) next[3];
                    if (nextType == TYPE_NOISE) {
                        i++;
                        continue;
                    }
                    if (nextType == TYPE_TITLE || nextType == TYPE_H1 || nextType == TYPE_H2 ||
                            nextType == TYPE_H3 || nextType == TYPE_REFERENCES) {
                        break;
                    }
                    content.append((String) next[0]).append("\n");
                    i++;
                }
                if (content.length() > 0) {
                    segments.add(content.toString().trim());
                }
            }
            // 二级标题
            else if (type == TYPE_H2) {
                segments.add((String) block[0]);
                i++;

                StringBuilder content = new StringBuilder();
                while (i < blocks.size()) {
                    Object[] next = blocks.get(i);
                    int nextType = (int) next[3];
                    if (nextType == TYPE_NOISE) {
                        i++;
                        continue;
                    }
                    if (nextType == TYPE_TITLE || nextType == TYPE_H1 || nextType == TYPE_H2 ||
                            nextType == TYPE_H3 || nextType == TYPE_REFERENCES) {
                        break;
                    }
                    content.append((String) next[0]).append("\n");
                    i++;
                }
                if (content.length() > 0) {
                    segments.add(content.toString().trim());
                }
            }
            // 三级标题
            else if (type == TYPE_H3) {
                segments.add((String) block[0]);
                i++;

                StringBuilder content = new StringBuilder();
                while (i < blocks.size()) {
                    Object[] next = blocks.get(i);
                    int nextType = (int) next[3];
                    if (nextType == TYPE_NOISE) {
                        i++;
                        continue;
                    }
                    if (nextType == TYPE_TITLE || nextType == TYPE_H1 || nextType == TYPE_H2 ||
                            nextType == TYPE_H3 || nextType == TYPE_REFERENCES) {
                        break;
                    }
                    content.append((String) next[0]).append("\n");
                    i++;
                }
                if (content.length() > 0) {
                    segments.add(content.toString().trim());
                }
            }
            // 普通正文
            else {
                StringBuilder content = new StringBuilder();
                while (i < blocks.size()) {
                    Object[] next = blocks.get(i);
                    int nextType = (int) next[3];
                    if (nextType == TYPE_NOISE) {
                        i++;
                        continue;
                    }
                    if (nextType == TYPE_TITLE || nextType == TYPE_H1 || nextType == TYPE_H2 ||
                            nextType == TYPE_H3 || nextType == TYPE_REFERENCES) {
                        break;
                    }
                    content.append((String) next[0]).append("\n");
                    i++;
                }
                if (content.length() > 0) {
                    segments.add(content.toString().trim());
                }
            }
        }

        return segments;
    }
}
