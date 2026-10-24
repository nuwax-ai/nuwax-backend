package com.xspaceagi.knowledge.domain.docparser;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能分段扩展服务 - 对 SmartParsingAIExtService 分段结果的章节标题正则后处理层:
 * 识别 "第X章/一、二、/## 第X章/# 一、" 等章节标题做攒段与小段归并
 */
@Slf4j
@Service
public class SmartParsingExtService {

    @Resource
    private SmartParsingAIExtService smartParsingAIExtService;

    public List<String> pageSegments(String content, String docUrl) {
        //if(false) {
        //List<String> singleResult = smartParsingService.buildBigSegments(content, docUrl);
        List<String> singleResult = smartParsingAIExtService.buildBigSegments(content, docUrl);

            List<String> newData = new ArrayList<>();
            StringBuilder buidler = new StringBuilder();

            boolean enableChapterState = false;

            int count = 0;

            for (int i = 0; i < singleResult.size(); i++) {
                //buidler.append(singleResult.get(i));

                String current = singleResult.get(i).toString();

                // 保留5个章节标题正则（章节/小节起始匹配）
                //Pattern pattern_1 = Pattern.compile("^第[一二三四五六七八九十百千万零]+[章节]\\s+");
                Pattern pattern_1 = Pattern.compile("^第[一二三四五六七八九十百千万零]+[章]\\s+");
                Matcher matcher_1 = pattern_1.matcher(current);
                //Pattern pattern_2 = Pattern.compile("^##\\s+第[一二三四五六七八九十百千万零]+[章节]\\s+");
                Pattern pattern_2 = Pattern.compile("^##\\s+第[一二三四五六七八九十百千万零]+[章]\\s+");
                Matcher matcher_2 = pattern_2.matcher(current);
                Pattern pattern_3 = Pattern.compile("^[一-二三四五六七八九十百]+、.*");
                Matcher matcher_3 = pattern_3.matcher(current);
                //Pattern pattern_4 = Pattern.compile("^第[一-九十百]+[章节].*");
                Pattern pattern_4 = Pattern.compile("^第[一-九十百]+[章].*");
                Matcher matcher_4 = pattern_4.matcher(current);
                Pattern pattern_5 = Pattern.compile("^#\\s+(?:[一二三四五六七八九十百千万零]+[、]|[（(][一二三四五六七八九十百千万零]+[）)]).*");
                Matcher matcher_5 = pattern_5.matcher(current);

                boolean isChapter = current.startsWith("一、") || current.startsWith("二、") ||
                        current.startsWith("三、") || current.startsWith("四、") ||
                        current.startsWith("五、") || current.startsWith("六、") ||
                        current.startsWith("七、") || current.startsWith("八、") ||
                        current.startsWith("九、") || current.startsWith("十、") ||
                        matcher_1.find() || matcher_2.find() || matcher_3.find() ||
                        matcher_4.find() || matcher_5.find();

                if (i == singleResult.size() - 1) {
                    // 最后一次循环：先把本次数据存入 builder，再把暂存数据加入结果
                    buidler.append(current);
                    newData.add(buidler.toString());
                } else if (isChapter && current.length() < 200) {
                    // 章节标题较短（<200字）：先把已暂存数据加入结果，再新开 builder 暂存本章节标题，作为新一段开始
                    if (buidler.length() > 0) {
                        newData.add(buidler.toString());
                    }
                    buidler = new StringBuilder();
                    buidler.append(current);
                    enableChapterState = true;
                    count = count + 1;
                } else if (isChapter) {
                    // 章节内容较多（>=200字）：先把已暂存数据加入结果，本章节单独作为一个分段
                    count = count + 1;
                    if (buidler.length() > 0) {
                        newData.add(buidler.toString());
                    }
                    buidler = new StringBuilder();
                    newData.add(current);
                    enableChapterState = false;
                } else {
                    // 普通内容
                    if (enableChapterState) {
                        buidler.append(current);
                        if (buidler.length() > 500) {
                            newData.add(buidler.toString());
                            buidler = new StringBuilder();
                            enableChapterState = false;
                        }
                    } else {
                        buidler.append(current);

                        // 🔧 修复：合理的小分段合并逻辑，避免形成超大分段
                        if(newData.size() > 0 && newData.get(newData.size()-1).length() < 1000) {
                            // 只在前一个分段确实很小（<1000字符）且当前内容也很短（<300字符）时才考虑合并
                            if(buidler.length() > 0 && buidler.length() < 300) {
                                String lastSegment = newData.get(newData.size()-1).toString();
                                String currentSegment = buidler.toString();

                                // 检查合并后的长度，避免超过2000字符
                                if(lastSegment.length() + currentSegment.length() <= 2000) {
                                    // 合并到前一个分段
                                    String mergedSegment = lastSegment + currentSegment;
                                    newData.remove(newData.size()-1);
                                    newData.add(mergedSegment);
                                    buidler = new StringBuilder();
                                    continue;
                                }
                            }
                        }

                        // 正常添加新分段
                        newData.add(buidler.toString());
                        buidler = new StringBuilder();
                    }
                }

            }

            if(count == 0) {
                buidler = new StringBuilder();
                newData = new ArrayList<>();
                for (int i = 0; i < singleResult.size(); i++) {
                    buidler.append(singleResult.get(i).toString());
                    if (i == singleResult.size() - 1) {
                        newData.add(buidler.toString());
                        buidler = new StringBuilder();
                    } else {
                        if(singleResult.get(i).length() < 100) {

                        } else {
                            if(buidler.length() > 400) {
                                newData.add(buidler.toString());
                                buidler = new StringBuilder();
                            }

                        }
                    }
                }
            }

            /*
            for (int i = 0; i < singleResult.size(); i++) {


                if(isChapterTitleFormat(singleResult.get(i).toString().replace("\n", "").replace("\r", ""))) {
                    System.out.println(singleResult.get(i));
                    newData.add(buidler.toString());
                    buidler = new StringBuilder();
                    buidler.append(singleResult.get(i));
                } else {
                    buidler.append(singleResult.get(i));
                }*/

                /*
                if(i == singleResult.size() - 1) {
                    buidler.append(singleResult.get(i));
                    newData.add(buidler.toString());
                } else {
                    Pattern pattern = Pattern.compile("^第[一二三四五六七八九十百千万零]+[章节]\\s+");
                    Matcher matcher = pattern.matcher(singleResult.get(i).toString());

                    if (matcher.find()) {
                        //System.out.println(singleResult.get(i));
                        if(newData.size() > 1) {
                            if(newData.get(newData.size()-1).length() < 350) {
                                String alldata = newData.get(newData.size()-2) + newData.get(newData.size()-1);
                                newData.remove(newData.size()-2);
                                newData.remove(newData.size()-1);
                                newData.add(alldata);
                            }
                        }

                        newData.add(buidler.toString());
                        buidler = new StringBuilder();
                        buidler.append(singleResult.get(i));
                    } else {

                        String regex = "^##\\s+第[一二三四五六七八九十百千万零]+[章节]\\s+";

                        pattern = Pattern.compile(regex);
                        matcher = pattern.matcher(singleResult.get(i));

                        if (matcher.find()) {

                            if(newData.size() > 1) {
                                if(newData.get(newData.size()-1).length() < 350) {
                                    String alldata = newData.get(newData.size()-2) + newData.get(newData.size()-1);
                                    newData.remove(newData.size()-2);
                                    newData.remove(newData.size()-1);
                                    newData.add(alldata);
                                }
                            }

                            newData.add(buidler.toString());
                            buidler = new StringBuilder();
                            buidler.append(singleResult.get(i));
                        } else if(buidler.length() > 500 && !(buidler.toString().endsWith(",") || buidler.toString().endsWith("，"))) {
                            newData.add(buidler.toString());
                            buidler = new StringBuilder();
                            buidler.append(singleResult.get(i));
                        } else {
                            buidler.append(singleResult.get(i));
                        }
                    }
                }


                buidler.append(singleResult.get(i));

                if(i == singleResult.size() - 1) {
                    newData.add(buidler.toString());
                } else  {
                     if(buidler.length() > 500) {
                        newData.add(buidler.toString());
                        buidler = new StringBuilder();
                    } else {
                    }
                }
            }*/

            /*
            List<String> newData2 = new ArrayList<>();
            StringBuilder buidler2 = new StringBuilder();
            //Collections.reverse(newData);
            for (int i = 0; i < newData.size(); i++) {
                if(i == newData.size() - 1) {
                    buidler2.append(newData.get(i));
                    newData2.add(buidler2.toString());
                } else  {
                    if(buidler2.length() > 500) {
                        newData2.add(buidler2.toString());
                        buidler2 = new StringBuilder();
                        buidler2.append(newData.get(i));
                    } else {
                        buidler2.append(newData.get(i));
                    }
                }
            }*/

            //Collections.reverse(newData2);
            singleResult = newData;

            return singleResult;
    }
}
