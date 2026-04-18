package com.xspaceagi.knowledge.domain.docparser;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.knowledge.core.spec.utils.Constants;
import com.xspaceagi.knowledge.domain.model.KnowledgeRawSegmentModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeRawSegmentRepository;
import com.xspaceagi.knowledge.sdk.enums.SegmentEnum;
import com.xspaceagi.knowledge.sdk.vo.SegmentConfigModel;
import com.xspaceagi.system.spec.common.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 智能分段的处理类
 */

@Slf4j
@Service
public class FileParseExtService {

    @Resource
    private IKnowledgeRawSegmentRepository knowledgeRawSegmentRepository;

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private PdfSegmentationServiceNative pdfSegmentationServiceNative;

    @Resource
    private FileParseService fileParseService;

    private String extractAndCleanJson(String rawResult) {
        if (rawResult == null || rawResult.trim().isEmpty()) {
            return "[]";
        }

        // 查找JSON数组的开始和结束
        int startIndex = rawResult.indexOf('[');
        int endIndex = rawResult.lastIndexOf(']');

        if (startIndex == -1 || endIndex == -1) {
            log.warn("No valid JSON array, return empty");
            return "[]";
        }

        // 提取JSON部分
        String jsonContent = rawResult.substring(startIndex, endIndex + 1);

        // 清理常见的格式问题
        jsonContent = jsonContent.trim();

        // 移除可能的markdown代码块标记
        jsonContent = jsonContent.replaceAll("```json", "").replaceAll("```", "");

        // 移除多余的空白字符，但保留字符串内部的空格
        jsonContent = jsonContent.replaceAll("\\s+", " ").trim();

        // 修复可能的JSON格式问题（如缺少逗号等）
        jsonContent = jsonContent.replaceAll("\\]\\s*\\[", "],[");

        return jsonContent;
    }

    public void parseRawTxt(FileParseRequest fileParseRequest,
                            UserContext userContext, String docUrl) {
        Long kbId = fileParseRequest.getKbId();
        Long docId = fileParseRequest.getDocId();
        Long spaceId = fileParseRequest.getSpaceId();
        String content = fileParseRequest.getContent();
        SegmentConfigModel segmentConfig = fileParseRequest.getSegmentConfig();

        List<String> segments = new ArrayList<>();
        log.info("Splitting paragraphs...");
        if(false) {
            //System.out.println("=====>文章比较小采用ai来进行文章分段");
            String sysPrompt = "请分析以下文章内容，按照语义逻辑将其划分为不同的一级章节。\n" +
                    "划分要求：\n" +
                    "1. 识别文章的主题转换点。\n" +
                    "2. 忽略无关的过渡句。\n" +
                    "3. 输出格式为 JSON 数组，每个元素是一个章节的内容。\n" +
                    "4. 每个章节应该包含该主题下的完整内容。\n" +
                    "5. 章节划分应该基于内容的逻辑关系和主题连贯性。\n" +
                    "6. 返回的JSON格式必须正确，不要包含任何额外文本或解释。\n" +
                    "7. 返回的JSON格式需要去掉换行符号。\n" +
                    //"7. 返回的JSON格式为：{\"chapters\":[\"2026年寒假假期即将来临，为了让自己度过一个健康、文明、平安、充实的假期，请同学们认真阅读相关注意事项，并遵照执行。\",\"离校同学需登录教育质量管理系统（EQ平台）\"]}。其中chapters为固定对象名称方便后面解析\n" +
                    "8. 实例：返回的JSON格式为：{\"chapters\":[\"章节一内容1\",\"章节一内容2\"]}。说明：其中chapters为固定对象名称方便后面解析\n" +
                    //"9. 需要阅读的文章地址为：" + docUrl + "?x="+ UUID.randomUUID().toString().replaceAll("-", "") + "\n" +
                    //"9.请严格按照文章地址中的内容进行拆分，要求文字需要和文章的文字保持一致。\n" +
                    //"10.请按原文的内容全部划分之后给我。\n" +
                    //"11.需要提取文章里面全部的文字，不要有遗漏。\n" +
                    "9.请按文章中第一级的大章节来划分，章节的划分力度需要粗一点。\n" +
                    "10.如遇到有大的章节中存在类似一、二、三等大写数字的关键文字，请把一、到二、中间的部分作为一个章节内容存储作为一个元素。\n" +
                    "11.如果单个章节划分的元素文字小于500字，请合并到下一个章节的元素中，保证每个章节的划分文字需要合理。\n" +
                    "\n" +
                    "文章内容：\n" +
                    "{{text}}";
            String userPrompt = content;
            //String userPrompt = "请先提取这篇'" + docUrl + "?x="+ UUID.randomUUID().toString().replaceAll("-", "") + "'文章的全部文字，不要有任何遗漏文字, 然后将文章提取出来的全部文字按章节进行分段，并将分段的结果以JSON格式返回给我";
            //System.out.println("docUrl:" + docUrl);

            Map<String, String> result = modelApplicationService.call(sysPrompt, userPrompt,
                    new org.springframework.core.ParameterizedTypeReference<Map<String, String>>() {
                    });
            //chapters
            String json = null;
            if(result.containsKey("chapters")) {
                json = result.get("chapters");
            }
            json = extractAndCleanJson(json);
            //System.out.println("result:" + json);
            if(!JSON.isValid(json)) {
                JSONArray jsonArray = JSONArray.parseArray(json);
                StringBuilder stringBuilder = new StringBuilder();
                if(jsonArray !=null && jsonArray.size() > 0) {
                    for(int i=0; i<jsonArray.size(); i++) {
                        String str = jsonArray.get(i).toString();
                        //jsonArray.add(str);
                        //str = str.replaceAll("\r\n", "\n").replaceAll("\r", "\n").replaceAll("\n", "");
                        stringBuilder.append(str);
                        if(stringBuilder.length() > 500) {
                            segments.add(stringBuilder.toString());
                            stringBuilder = new StringBuilder();
                        } else if(i == jsonArray.size() - 1) {
                            segments.add(stringBuilder.toString());
                            stringBuilder = new StringBuilder();
                        }
                    }
                }
            } else {
                segments.add(result.get("chapters").toString());
            }
        } else {
            boolean needloadWord = false;
            if(docUrl.indexOf(".docx") != -1 || docUrl.indexOf(".doc") != -1) {
                try {
                    needloadWord = this.hasHeadingStyles(docUrl);
                    if(needloadWord == true) {
                        List<String> tempArray = new ArrayList<>();
                        //segments = parseWordByStructure(docUrl);
                        tempArray = parseWordByStructure(docUrl);
                        StringBuilder stringBuilder = new StringBuilder();
                        if(tempArray !=null && tempArray.size() > 0) {
                            for(int i=0; i<tempArray.size(); i++) {
                                String str = tempArray.get(i).toString();
                                stringBuilder.append(str);
                                if(stringBuilder.length() > 500) {
                                    segments.add(stringBuilder.toString());
                                    stringBuilder = new StringBuilder();
                                } else if(i == tempArray.size() - 1) {
                                    segments.add(stringBuilder.toString());
                                    stringBuilder = new StringBuilder();
                                }
                            }
                        }

                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    needloadWord = false;
                }
            } else if(docUrl.indexOf(".pdf") != -1 || docUrl.indexOf(".PDF") != -1) {
                try {
                    List<String> tempArray = pdfSegmentationServiceNative.parseFromUrl(docUrl);
                    needloadWord = true;
                    StringBuilder stringBuilder = new StringBuilder();
                    for(int i=0; i<tempArray.size(); i++) {
                        String str = tempArray.get(i);
                        stringBuilder.append(str);
                        if(stringBuilder.length() > 500) {
                            segments.add(stringBuilder.toString());
                            stringBuilder = new StringBuilder();
                        } else if(i == tempArray.size() - 1) {
                            segments.add(stringBuilder.toString());
                            stringBuilder = new StringBuilder();
                        }
                    }

                    //pdf没有分出来，就用默认兜底的方式来分
                    if(segments != null && segments.size() == 1) {
                        needloadWord = false;
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if(needloadWord == false) {
                //System.out.println("=====>文章采用内部模型进行文章分段");
                segments = this.segmentByEmbedding(content);
            }
        }

        /*
        *
        * segments = semanticSegmentService.segmentByEmbedding(content);

        if(false) {
            String sysPrompt = "请分析以下文章内容，按照语义逻辑将其划分为不同的章节。\n" +
                    "要求：\n" +
                    "1. 识别文章的主题转换点。\n" +
                    "2. 忽略无关的过渡句。\n" +
                    "3. 输出格式为 JSON 数组，每个元素是一个章节的内容。\n" +
                    "4. 每个章节应该包含该主题下的完整内容。\n" +
                    "5. 章节划分应该基于内容的逻辑关系和主题连贯性。\n" +
                    "6. 返回的JSON格式必须正确，不要包含任何额外文本或解释。\n" +
                    "7. 返回的JSON格式需要去掉换行符号。\n" +
                    //"7. 返回的JSON格式为：{\"chapters\":[\"2026年寒假假期即将来临，为了让自己度过一个健康、文明、平安、充实的假期，请同学们认真阅读相关注意事项，并遵照执行。\",\"离校同学需登录教育质量管理系统（EQ平台）\"]}。其中chapters为固定对象名称方便后面解析\n" +
                    "8. 实例：返回的JSON格式为：{\"chapters\":[\"章节一内容1\",\"章节一内容2\"]}。说明：其中chapters为固定对象名称方便后面解析\n" +
                    "9. 需要阅读的文章地址为：" + docUrl + "?x="+ UUID.randomUUID().toString().replaceAll("-", "") + "\n" +
                    "10.请严格按照文章地址中的内容进行拆分，要求文字需要和文章的文字保持一致。\n" +
                    "12.请按原文的内容全部划分之后给我。\n" +
                    "13.需要提取文章里面全部的文字，不要有遗漏。\n" +
                    "\n" +
                    "文章内容：\n" +
                    "{{text}}";
            //String userPrompt = content;
            String userPrompt = "请先提取这篇'" + docUrl + "?x="+ UUID.randomUUID().toString().replaceAll("-", "") + "'文章的全部文字，不要有任何遗漏文字, 然后将文章提取出来的全部文字按章节进行分段，并将分段的结果以JSON格式返回给我";
            System.out.println("docUrl:" + docUrl);

            Map<String, String> result = modelApplicationService.call(sysPrompt, userPrompt,
                    new org.springframework.core.ParameterizedTypeReference<Map<String, String>>() {
                    });
            //chapters
            String json = null;
            if(result.containsKey("chapters")) {
                json = result.get("chapters");
            }
            json = extractAndCleanJson(json);

            System.out.println("result:" + json);

            JSONArray jsonArray = JSONArray.parseArray(json);
            if(jsonArray !=null && jsonArray.size() > 0) {
                for(int i=0; i<jsonArray.size(); i++) {
                    String str = jsonArray.get(i).toString();
                    //jsonArray.add(str);
                    //str = str.replaceAll("\r\n", "\n").replaceAll("\r", "\n").replaceAll("\n", "");
                    segments.add(str);
                }
            }
        }
        *
        * */

        if (segments == null || segments.size() == 0) {
            segmentConfig.setSegment(SegmentEnum.WORDS);
            fileParseService.parseRawTxt(fileParseRequest, userContext);
            return;
        }

        boolean isTrim = segmentConfig.getIsTrim();
        int sortIndex = 0;
        for (String s : segments) {
            if (StringUtils.isBlank(s)) {
                //分段出来的内容,是空白,无意义
                continue;
            }
            //判断非空的分段内容长度,如果长度小于等于 SEGMENT_MIN_WORDS (比如:10),不处理
            var strLen = s.length();
            if (strLen < Constants.SEGMENT_MIN_WORDS) {
                log.info("Segment length <= {}, skip, segment: {}", Constants.SEGMENT_MIN_WORDS, s);
                continue;
            }
            KnowledgeRawSegmentModel segment = new KnowledgeRawSegmentModel();
            String cleaned = isTrim ? s.replaceAll("\\s+", " ") : s;
            segment.setRawTxt(cleaned);
            segment.setDocId(docId);
            segment.setKbId(kbId);
            segment.setSortIndex(sortIndex);
            segment.setSpaceId(spaceId);

            this.knowledgeRawSegmentRepository.addInfo(segment, userContext);
            sortIndex++;
        }
    }

    public List<String> segmentByEmbedding(String text) {
        List<String> segments = splitByTitles(text);

        Pattern titlePattern = Pattern.compile(
                "^[一二三四五六七八九十]+、[^\\n]+$|" ,
                //"^[（一二三四五六七八九十]+）[^\\n]+$|" +
                //"^\\d+\\.\\s*[^\\n]+$",
                Pattern.MULTILINE
        );

        // 合并过短的分段到下一个分段
        //List<String> tempArray = mergeShortSegments(segments);
        List<String> tempArray = segments;
        StringBuilder stringBuilder = new StringBuilder();
        List<String> newSegmentArray = new ArrayList<>();
        if(tempArray != null && tempArray.size() > 0) {
            for(int i=0; i<tempArray.size(); i++) {
                String str = tempArray.get(i).toString();
                if (titlePattern.matcher(str).matches()) {
                    newSegmentArray.add(stringBuilder.toString());
                    stringBuilder = new StringBuilder();
                }
                stringBuilder.append(str);

                //if(stringBuilder.length() > 500 && str.length() > 50) {
                if(stringBuilder.length() > 500 ) {
                    newSegmentArray.add(stringBuilder.toString());
                    stringBuilder = new StringBuilder();
                } else if(i == tempArray.size() - 1) {
                    newSegmentArray.add(stringBuilder.toString());
                    stringBuilder = new StringBuilder();
                }
            }
        }
        return newSegmentArray;
    }

    private List<String> mergeShortSegments(List<String> segments) {
        List<String> result = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            String current = segments.get(i);

            if (current.length() < 100 && i < segments.size() - 1) {
                String next = segments.get(i + 1);
                String merged = current + "\n" + next;
                result.add(merged);
                i++;
            } else {
                result.add(current);
            }
        }

        return result;
    }

    private List<String> splitByTitles(String text) {
        List<String> segments = new ArrayList<>();

        if(true) {
            // 使用正则匹配 ## 一、## 二、等作为分割点，关键词保留在每段开头
            Pattern pattern = Pattern.compile("(## [一二三四五六七八九十]+、)");
            Matcher matcher = pattern.matcher(text);

            // 使用 List 构建分割后的结果
            //java.util.List<String> segments = new java.util.ArrayList<>();
            int lastEnd = 0;

            while (matcher.find()) {
                // 添加匹配位置之前的文本（不包含关键词）
                String segment = text.substring(lastEnd, matcher.start());
                if (!segment.isEmpty()) {
                    segments.add(segment);
                }
                lastEnd = matcher.start();
            }
            // 添加最后剩余的文本（包含关键词）
            if (lastEnd < text.length()) {
                segments.add(text.substring(lastEnd));
            }
            if(segments.size() > 2) {
                return segments;
            }
        }

        segments = new ArrayList<>();

        // 匹配中文数字标题、括号标题和数字标题
        Pattern titlePattern = Pattern.compile(
                //"第[一二三四五六七八九十]+章|"+
                "^.*第[一二三四五六七八九十]+章.*\\s*$|"+
                        "^[一二三四五六七八九十]+、[^\\n]+$|" +
                        "^[（一二三四五六七八九十]+）[^\\n]+$|" +
                        "^\\d+\\.\\s*[^\\n]+$",
                Pattern.MULTILINE
        );

        String[] lines = text.split("\\n");
        StringBuilder currentSegment = new StringBuilder();

        for (String line : lines) {
            String trimmedLine = line.trim();
            // 遇到标题时，保存当前分段并开始新分段
            if (titlePattern.matcher(trimmedLine).matches()) {
                if (currentSegment.length() > 0) {
                    segments.add(currentSegment.toString().trim());
                    currentSegment = new StringBuilder();
                }
            }

            if (!trimmedLine.isEmpty()) {
                currentSegment.append(trimmedLine).append("\n");
            }
        }

        // 添加最后一个分段
        if (currentSegment.length() > 0) {
            segments.add(currentSegment.toString().trim());
        }

        // 如果分段没有成功保底的方式按结束符号+换行符号为分割符号分割
        // 以常用标点符号结束符+换行符作为分割符进行分割
        // 常用结束标点：句号。 感叹号！ 问号？ 分号； 中文引号" 英文句号. 英文感叹号! 英文问号? 英文分号;
        if(segments != null && segments.size() < 5) {
            /*
            String[] segments_text = text.split("第[一二三四五六七八九十]+章");
            if(segments_text.length > 2) {
                segments = new ArrayList<>();
                for (int i = 0; i < segments_text.length; i++) {
                    segments.add(segments_text[i].trim());
                }
            }*/
            // 使用正则捕获组来保留分割关键词
            // 在匹配的位置进行分割，关键词保留在下一段的开头
            Pattern pattern = Pattern.compile("(第[一二三四五六七八九十]+章)");
            Matcher matcher = pattern.matcher(text);

            // 使用 List 构建分割后的结果
            //java.util.List<String> segments = new java.util.ArrayList<>();
            int lastEnd = 0;

            List<String> temp_segments = new ArrayList<>();

            while (matcher.find()) {
                // 添加匹配位置之前的文本（不包含关键词）
                String segment = text.substring(lastEnd, matcher.start());
                if (!segment.isEmpty()) {
                    //segments.add(segment);
                    temp_segments.add(segment);
                }
                lastEnd = matcher.start();
            }
            // 添加最后剩余的文本（包含关键词）
            if (lastEnd < text.length()) {
                //segments.add(text.substring(lastEnd));
                temp_segments.add(text.substring(lastEnd));
            }

            if(temp_segments != null && temp_segments.size() > 2) {
                segments = new ArrayList<>();
                segments.addAll(temp_segments);
            }
        }
        if(segments != null && segments.size() == 1) {
            segments = new ArrayList<>();
            //String[] segments_text = text.split("(?<=[。！？；\".!?;])\\n");
            //String[] segments_text = text.split("(?<=[。！？；\".!?;])\\n");
            String[] segments_text = text.split("\\n");
            // 打印分割后的数组
            StringBuilder stringBuilder = new StringBuilder();
            for (int i = 0; i < segments_text.length; i++) {
                stringBuilder.append(segments_text[i].trim());
                if(segments_text[i].trim().endsWith("。") ||
                        //segments_text[i].trim().endsWith("！") ||
                        segments_text[i].trim().endsWith("？") ||
                        segments_text[i].trim().endsWith("；") ||
                        //segments_text[i].trim().endsWith("\"") ||
                        segments_text[i].trim().endsWith(".") ||
                        //segments_text[i].trim().endsWith("!") ||
                        segments_text[i].trim().endsWith("?") ||
                        segments_text[i].trim().endsWith(";")) {
                    segments.add(stringBuilder.toString());
                    stringBuilder = new StringBuilder();
                }

            }

            if(segments != null && segments.size() == 1) {
                segments = new ArrayList<>();
                //String[] segments_text = text.split("(?<=[。！？；\".!?;])");
                //String[] _segments_text = text.split("(?<=[。！？；\".!?;])");
                //String[] _segments_text = text.split("(?<=[。！？；\".!?;])");
                //String[] _segments_text = text.split("(?<=[。！？；.!?;])");
                String[] _segments_text = text.split("(?<=[。？；.?;])");
                for (int i = 0; i < _segments_text.length; i++) {
                    segments.add(_segments_text[i].trim());
                }
            }

        }

        if(segments != null && segments.size() == 0) {
            String[] array = text.split(" ");
            if(array.length > 10) {
                segments = new ArrayList<>();
                segments = java.util.Arrays.asList(array);
            }
        }

        return segments;
    }

    private List<String> splitByNLP(String text) {
        List<String> sentences = new ArrayList<>();

        // 按句末标点切分（简单的正则，实际建议用 NLP 库分句）
        String[] rawSentences = text.split("(?<=[。！？；!?])");

        for (String sentence : rawSentences) {
            String trimmed = sentence.trim();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }

        return sentences;
    }

    private List<String> mergeSentencesByLength(List<String> sentences, int minLength, int maxLength) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        // 按句子合并，确保每个分段在指定长度范围内
        for (String sentence : sentences) {
            String potential = current.toString() + sentence;

            if (potential.length() <= maxLength) {
                current.append(sentence);
            } else {
                if (current.length() >= minLength) {
                    result.add(current.toString());
                    current = new StringBuilder(sentence);
                } else {
                    current.append(sentence);
                }
            }
        }

        // 处理最后一个分段
        if (current.length() > 0) {
            if (current.length() < minLength && !result.isEmpty()) {
                // 最后一个分段过短，尝试与上一个分段合并
                String last = result.get(result.size() - 1);
                String merged = last + current.toString();
                if (merged.length() <= maxLength) {
                    result.set(result.size() - 1, merged);
                } else {
                    result.add(current.toString());
                }
            } else {
                result.add(current.toString());
            }
        }

        return result;
    }

    //---------------------------------------------------------word文档解析代码----------------------------------------------------------------------------
    /**
     * 按文档结构解析 Word 文档（按章节分段）
     * 根据标题识别来切分文档，每个章节作为一个分段
     * @param urlStr 文档 URL
     * @return 分段后的章节列表
     */
    public List<String> parseWordByStructure(String urlStr) throws Exception {
        log.info("Download Word from URL: {}", urlStr);

        URL url = new URL(urlStr);
        String fileName = getFileNameFromUrl(urlStr);
        Path tempFile = Files.createTempFile("word_", "_" + fileName);

        try (InputStream in = url.openStream()) {
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("Download done, temp file: {}", tempFile);

            List<String> segments = parseWordByStructure(tempFile.toFile());

            Files.deleteIfExists(tempFile);
            log.info("Temp file deleted");

            return segments;
        } catch (Exception e) {
            Files.deleteIfExists(tempFile);
            throw new Exception("从 URL 解析 Word 文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 Word 文件（内部方法）
     * @param file Word 文件
     * @return 分段后的章节列表
     */
    private List<String> parseWordByStructure(File file) throws Exception {
        String fileName = file.getName().toLowerCase();

        if (fileName.endsWith(".docx")) {
            return parseDocxByStructure(file);
        } else if (fileName.endsWith(".doc")) {
            return parseDocByStructure(file);
        } else {
            throw new IllegalArgumentException("Unsupported file format: " + fileName);
        }
    }

    /**
     * 检查 Word 文档是否包含标题样式（轻量级判断，不进行完整解析）
     * @param urlStr 文档 URL
     * @return true 表示有标题样式，false 表示无标题样式（纯文本）
     */
    public boolean hasHeadingStyles(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        String fileName = getFileNameFromUrl(urlStr);
        Path tempFile = Files.createTempFile("word_", "_" + fileName);

        try (InputStream in = url.openStream()) {
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            boolean hasStyles = hasHeadingStyles(tempFile.toFile());
            Files.deleteIfExists(tempFile);
            return hasStyles;
        } catch (Exception e) {
            Files.deleteIfExists(tempFile);
            throw new Exception("从 URL 检查标题样式失败: " + e.getMessage(), e);
        }
    }

    /**
     * 检查 Word 文件是否包含标题样式（内部方法）
     * @param file Word 文件
     * @return true 表示有标题样式，false 表示无标题样式（纯文本）
     */
    private boolean hasHeadingStyles(File file) throws Exception {
        String fileName = file.getName().toLowerCase();

        if (fileName.endsWith(".docx")) {
            return checkDocxHeadingStyles(file);
        } else if (fileName.endsWith(".doc")) {
            return checkDocHeadingStyles(file);
        } else {
            throw new IllegalArgumentException("Unsupported file format: " + fileName);
        }
    }

    /**
     * 解析 .docx 格式文档并按结构分段
     * 同时使用大纲级别和样式名称来识别标题
     * 遇到标题时开始新的分段，标题和后续内容都在同一个分段中
     * 分段数和标题数一致
     */
    private List<String> parseDocxByStructure(File file) throws Exception {
        List<String> segments = new ArrayList<>();

        try (InputStream is = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(is)) {

            XWPFStyles styles = document.getStyles();
            StringBuilder currentSegment = new StringBuilder();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph) {
                    XWPFParagraph paragraph = (XWPFParagraph) element;
                    String text = paragraph.getText().trim();

                    if (text.isEmpty()) {
                        continue;
                    }

                    boolean isHeading = isHeadingParagraph(paragraph, styles);

                    if (isHeading) {
                        if (currentSegment.length() > 0) {
                            segments.add(currentSegment.toString().trim());
                        }
                        currentSegment = new StringBuilder();
                    }

                    currentSegment.append(text).append("\n");

                } else if (element instanceof XWPFTable) {
                    XWPFTable table = (XWPFTable) element;
                    String tableText = extractTableText(table);
                    currentSegment.append(tableText).append("\n");
                }
            }

            if (currentSegment.length() > 0) {
                segments.add(currentSegment.toString().trim());
            }
        }

        return segments;
    }

    /**
     * 解析 .doc 格式（旧版 Word）文档并按结构分段
     * 使用 HWPF 库处理 .doc 格式
     */
    private List<String> parseDocByStructure(File file) throws Exception {
        List<String> segments = new ArrayList<>();

        try (InputStream is = new FileInputStream(file);
             HWPFDocument document = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(document)) {

            String[] paragraphs = extractor.getParagraphText();

            StringBuilder currentSegment = new StringBuilder();

            for (String paragraph : paragraphs) {
                String text = paragraph.trim();
                if (text.isEmpty()) {
                    continue;
                }

                if (isTitleParagraph(text)) {
                    if (currentSegment.length() > 0) {
                        segments.add(currentSegment.toString().trim());
                    }
                    currentSegment = new StringBuilder();
                }

                currentSegment.append(text).append("\n");
            }

            if (currentSegment.length() > 0) {
                segments.add(currentSegment.toString().trim());
            }
        }

        return segments;
    }

    /**
     * 检查 .docx 文档是否包含标题样式（只遍历一次，不进行完整解析）
     * 同时检测大纲级别和样式名称
     * @param file .docx 文件
     * @return true 表示有标题样式，false 表示无标题样式（纯文本）
     */
    private boolean checkDocxHeadingStyles(File file) throws Exception {
        try (InputStream is = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(is)) {

            XWPFStyles styles = document.getStyles();

            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph) {
                    XWPFParagraph paragraph = (XWPFParagraph) element;

                    if (isHeadingParagraph(paragraph, styles)) {
                        return true;
                    }
                }
            }

            return false;
        }
    }

    /**
     * 判断段落是否为标题段落
     * 优先使用大纲级别，其次使用样式名称
     * @param paragraph 段落
     * @param styles 文档样式
     * @return true 表示是标题段落
     */
    private boolean isHeadingParagraph(XWPFParagraph paragraph, XWPFStyles styles) {
        try {
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr ppr = paragraph.getCTP().getPPr();
            if (ppr != null && ppr.getOutlineLvl() != null) {
                int outlineLevel = ppr.getOutlineLvl().getVal().intValue();
                if (outlineLevel >= 0 && outlineLevel <= 8) {
                    return true;
                }
            }
        } catch (Exception e) {
            // 忽略没有大纲级别的段落
        }

        String styleId = paragraph.getStyle();
        if (styleId != null && styles != null) {
            XWPFStyle style = styles.getStyle(styleId);
            if (style != null) {
                String styleName = style.getName();
                if (styleName != null) {
                    String lowerName = styleName.toLowerCase();
                    if (lowerName.contains("heading") || lowerName.contains("标题")) {
                        return true;
                    }
                }
                String baseStyleId = style.getBasisStyleID();
                if (baseStyleId != null) {
                    String lowerBaseId = baseStyleId.toLowerCase();
                    if (lowerBaseId.contains("heading")) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * 检查 .doc 文档是否包含标题样式（只遍历一次，不进行完整解析）
     * @param file .doc 文件
     * @return true 表示有标题样式，false 表示无标题样式（纯文本）
     */
    private boolean checkDocHeadingStyles(File file) throws Exception {
        try (InputStream is = new FileInputStream(file);
             HWPFDocument document = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(document)) {

            String[] paragraphs = extractor.getParagraphText();

            for (String paragraph : paragraphs) {
                String text = paragraph.trim();
                if (!text.isEmpty() && isTitleParagraph(text)) {
                    return true;
                }
            }

            return false;
        }
    }

    /**
     * 提取表格的文本内容
     * 将表格的每个单元格内容用制表符分隔，行与行之间用换行分隔
     * @param table 表格对象
     * @return 表格的文本表示
     */
    private String extractTableText(XWPFTable table) {
        StringBuilder sb = new StringBuilder();
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                sb.append(cell.getText()).append("\t");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 判断 .doc 文档中的段落是否为标题（.doc 格式）
     * 使用 HWPF 的样式信息来判断
     * @param text 段落文本
     * @return true 表示是标题段落
     */
    private boolean isTitleParagraph(String text) {
        return false;
    }

    /**
     * 从 URL 中提取文件名
     * @param urlStr URL 字符串
     * @return 文件名
     */
    private String getFileNameFromUrl(String urlStr) {
        String path = urlStr.substring(urlStr.lastIndexOf('/') + 1);
        if (path.isEmpty()) {
            return "document.docx";
        }
        return path;
    }
    //---------------------------------------------------------------------------------------------------------------------------------------------------

}
