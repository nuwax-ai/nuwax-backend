package com.xspaceagi.knowledge.domain.docparser;

import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.knowledge.domain.dto.SmartParsingTopicGenDto;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能分段引擎(当前生效版): 递归分段 + 段内二次细分 + 兜底策略编排。
 * 文本切分工具见 SegmentTextSupport,LLM结果位置匹配见 SegmentPositionMatcher。
 */
@Slf4j
@Service
public class SmartParsingAIExtService {


    @Resource
    private ModelApplicationService modelApplicationService;


    /**
     * 递归智能分段方法（用于15000-150000字的内容）
     *
     * 核心思路：
     * 1. 调用coarseSplitBySentence对剩余内容进行粗分段
     * 2. 取第一条粗分段，发送给AI模型进行精细分段
     * 3. 如果粗分段有多条（非最后一轮）：
     *    - 移除模型分段的最后一条（因为粗分段边界是人工的，最后一段不可靠）
     *    - 用被移除分段的特征文字在原文中找到起始位置
     *    - 从该位置到原文末尾作为新的剩余内容，递归处理
     * 4. 如果粗分段只有一条（最后一轮）：
     *    - 保留模型分段的全部数据，递归结束
     *
     * @param remainingContent 当前需要处理的剩余文本内容
     * @param docUrl 文档URL
     * @return 完整的分段结果列表
     */
    private List<String> recursiveSmartSegment(String remainingContent, String docUrl, String content, boolean isFirstState) {
        if (remainingContent == null || remainingContent.trim().isEmpty()) {
            return new ArrayList<>();
        }

        // Step 1: 粗粒度分段
        List<String> coarseChunks = SegmentTextSupport.coarseSplitBySentence(remainingContent, 6000);

        if (coarseChunks.isEmpty()) {
            log.warn("recursiveSmartSegment: coarseSplitBySentence returned empty");
            return new ArrayList<>();
        }

        boolean isLastRound = (coarseChunks.size() == 1);
        String firstChunk = coarseChunks.get(0);

        log.info("recursiveSmartSegment: remaining {} chars, {} coarse chunks, isLastRound={}",
                remainingContent.length(), coarseChunks.size(), isLastRound);

        // Step 2: 取第一条粗分段，调用AI模型分段
        List<String> aiSegments;
        try {
            aiSegments = this.segments(firstChunk, docUrl);

            if(aiSegments.size() == 1) {
                aiSegments = this.segments(firstChunk, docUrl);
            }
            if(aiSegments.size() == 1) {
                aiSegments = this.segments(firstChunk, docUrl);
            }
            if(aiSegments.size() == 1) {
                aiSegments = this.segments(firstChunk, docUrl);
            }

            if(isFirstState == true) {
                List<String> newSegments = new ArrayList<>();
                if(aiSegments != null && aiSegments.size() > 0) {
                    for(String nextSegment : aiSegments) {

                        if(nextSegment.length() > 1500) {
                            List<String> array = this.segments2(nextSegment, docUrl);
                            if(array != null && array.size() == 1) {
                                array = this.segments3(nextSegment, docUrl);
                            }
                            /*
                            StringBuilder buidler = new StringBuilder();
                            List<String> temp = new ArrayList<>();
                            if(array != null && array.size() > 0) {
                                for(int j=0; j<array.size(); j++) {
                                    buidler.append(array.get(j));

                                    if(j == array.size() - 1) {
                                        temp.add(buidler.toString());
                                    } else  {
                                        if(buidler.length() > 500) {
                                            temp.add(buidler.toString());
                                            buidler = new StringBuilder();
                                        } else {
                                        }
                                    }
                                }
                            }*/

                            newSegments.addAll(array);
                        } else {
                            newSegments.add(nextSegment);
                        }
                        //newSegments.add(nextSegment);
                    }
                }
                aiSegments = newSegments;
            }

        } catch (Exception e) {
            log.warn("recursiveSmartSegment: AI call failed: {}", e.getMessage());
            List<String> fallback = new ArrayList<>();
            fallback.add(remainingContent);
            return fallback;
        }

        if (aiSegments == null || aiSegments.isEmpty()) {
            log.warn("recursiveSmartSegment: AI returned empty segments");
            List<String> fallback = new ArrayList<>();
            fallback.add(remainingContent);
            return fallback;
        }

        // Step 3: 如果是最后一轮（粗分段只有一条），保留所有AI分段
        if (isLastRound) {
            log.info("recursiveSmartSegment: BASE CASE, keeping all {} AI segments", aiSegments.size());
            return aiSegments;
        }

        /*
        // Step 4: 非最后一轮 - 移除最后一条AI分段（边界不可靠）
        if (aiSegments.size() <= 1) {
            // AI只返回了一个分段，无法移除，直接当作最后一轮处理
            log.warn("recursiveSmartSegment: AI returned only 1 segment in non-final round, treating as last");
            return aiSegments;
        }*/

        String removedLastSegment = aiSegments.get(aiSegments.size() - 1);
        List<String> keptSegments = new ArrayList<>(aiSegments.subList(0, aiSegments.size() - 1));

        log.info("recursiveSmartSegment: removed last AI segment ({} chars), keeping {} segments",
                removedLastSegment.length(), keptSegments.size());

        // Step 5: 用被移除分段的特征文字在剩余原文中找到起始位置
        int position = SegmentPositionMatcher.findStartPositionInContent(removedLastSegment, remainingContent, content);

        /*
        if (position < 0 || position >= remainingContent.length()) {
            // 找不到位置，用粗分段的边界作为fallback
            int firstChunkEnd = firstChunk.length();
            log.warn("recursiveSmartSegment: position finding failed, using coarse chunk boundary at {}",
                    firstChunkEnd);
            position = firstChunkEnd;
        }*/

        // Step 6: 从找到的位置到原文末尾作为新的剩余内容
        //String newRemainingContent = remainingContent.substring(position);
        String newRemainingContent = content.substring(position);
        //coarseChunks = SegmentTextSupport.coarseSplitBySentence(newRemainingContent, 6000);
        //newRemainingContent = coarseChunks.get(0);

        log.info("recursiveSmartSegment: next round starts at position {}, new remaining {} chars",
                position, newRemainingContent.length());

        // 安全检查：避免无限递归
        if (newRemainingContent.length() >= remainingContent.length()) {
            log.warn("recursiveSmartSegment: remaining content not shrinking, stopping recursion");
            keptSegments.add(newRemainingContent);
            return keptSegments;
        }

        // Step 7: 递归处理剩余内容
        List<String> nextSegments = recursiveSmartSegment(newRemainingContent, docUrl, content, false);

        /*
        List<String> newSegments = new ArrayList<>();
        if(nextSegments != null && nextSegments.size() > 0) {
            for(String nextSegment : nextSegments) {
                if(nextSegment.length() > 2000) {
                    List<String> array = this.segments2(nextSegment, docUrl);

                    StringBuilder buidler = new StringBuilder();
                    List<String> temp = new ArrayList<>();
                    if(array != null && array.size() > 0) {
                        for(int j=0; j<array.size(); j++) {
                            buidler.append(array.get(j));

                            if(j == array.size() - 1) {
                                temp.add(buidler.toString());
                            } else  {
                                if(buidler.length() > 500) {
                                    temp.add(buidler.toString());
                                    buidler = new StringBuilder();
                                } else {
                                }
                            }
                        }
                    }

                    if(array != null && array.size() > 1) {
                        System.out.println(array.get(array.size()-1).length() );
                        if(array.get(array.size()-1).length() < 250) {
                            String temp2 = new String(array.get(array.size()-1));
                            String fullStr = array.get(array.size()-2) + temp2;
                            array.remove(array.get(array.size()-1));
                            array.remove(array.get(array.size()-2));
                            array.add(fullStr);
                        }
                    }

                    newSegments.addAll(array);
                } else {
                    newSegments.add(nextSegment);
                }
            }
        }*/

        // Step 8: 合并结果
        keptSegments.addAll(nextSegments);
        //keptSegments.addAll(newSegments);
        log.info("recursiveSmartSegment: round complete, {} total segments", keptSegments.size());

        return keptSegments;
    }

    public List<String> buildBigSegments(String content, String docUrl) {
        // 内容为空直接返回
        if (content == null || content.trim().isEmpty()) {
            log.warn("buildBigSegments: content is empty");
            return new ArrayList<>();
        }

        // 永久性配置错误预检: 租户默认对话模型不可用时,不进入 LLM 调用与后续重试/兜底,
        // 由上层将原因记录到文档处理结果并提示用户配置模型
        ModelConfigDto modelConfig = modelApplicationService.queryDefaultModelConfig();
        if (modelConfig == null) {
            throw new SmartParsingModelUnavailableException();
        }

        int contentLength = content.length();
        log.info("buildBigSegments: input {} chars", contentLength);

        // 分支1：超过10万字，直接用segmentByEmbedding分段（高效但精度低）
        /*
        if (contentLength > 150000) {
            log.info("buildBigSegments: {} chars > 150000, using segmentByEmbedding (high-efficiency mode)", contentLength);
            List<String> bigSegments = SegmentTextSupport.segmentByEmbedding(content);
            log.info("buildBigSegments: segmentByEmbedding produced {} segments", bigSegments.size());
            return bigSegments;
        }*/

        // 分支2：15000 ~ 150000字，使用递归智能分段（粗分段+AI分段递归）
        if (contentLength > 15000) {
            log.info("buildBigSegments: {} chars in [15000, 150000], using recursive smart segmentation", contentLength);

            try {
                content = content.replace("\n\n", "\n").replace("\n\n\n", "\n").replace("\n\n\n\n", "\n").replaceAll("-\\d+-", "").replaceAll("\\s*—\\s*\\d+\\s*—\\s*", "").toString();
                List<String> allSegments = recursiveSmartSegment(content, docUrl, content, true);

                List<String> newSegments = new ArrayList<>();
                if(allSegments != null && allSegments.size() > 0) {
                    for(String nextSegment : allSegments) {
                        if(nextSegment.length() > 1500) {
                            List<String> array = this.segments2(nextSegment, docUrl);
                            if(array != null && array.size() > 0 && array.size() == 1) {
                                array = this.segments2(nextSegment, docUrl);
                                if(array != null && array.size() > 0 && array.size() == 1) {
                                    array = this.segments3(nextSegment, docUrl);
                                    if(array != null && array.size() > 0 && array.size() == 1) {
                                        if(array.get(0).length() > 1500) {
                                            array = this.segments3(nextSegment, docUrl);
                                        }
                                    }
                                }
                            }
                            /*
                            StringBuilder buidler = new StringBuilder();

                            List<String> temp = new ArrayList<>();
                            if(array != null && array.size() > 0) {
                                for(int j=0; j<array.size(); j++) {
                                    String str = array.get(j);

                                    if(array.size() - 1 == j) {
                                        // 规则4：最后一次循环，先把本次数据存入 builder，再把暂存数据加入结果
                                        buidler.append(str);
                                        temp.add(buidler.toString());
                                    } else {
                                        Pattern pattern_3 = Pattern.compile("^[一-二三四五六七八九十百]+、.*");
                                        Matcher matcher_3 = pattern_3.matcher(str);
                                        if (matcher_3.find() && str.length() < 100 && !buidler.isEmpty()) {
                                            // 规则1：以（一、二、三）开头且较短，作为新一段开始：先把暂存数据加入结果，再新开 builder 暂存本次数据
                                            temp.add(buidler.toString());
                                            buidler = new StringBuilder();
                                            buidler.append(str);
                                        } else {
                                            // 规则2/3：先把本次数据存入 builder，再判断是否到 500 字临界值
                                            buidler.append(str);
                                            if (buidler.length() > 500) {
                                                // 超过临界值：把暂存数据加入结果，再新开 builder
                                                temp.add(buidler.toString());
                                                buidler = new StringBuilder();
                                            }
                                            // 否则继续暂存，不放入结果
                                        }
                                    }
                                }
                            }*/

                            //array = temp;

                            /*
                            if(array != null && array.size() > 1) {
                                System.out.println(array.get(array.size()-1).length() );
                                if(array.get(array.size()-1).length() < 250) {
                                    String temp2 = new String(array.get(array.size()-1));
                                    String fullStr = array.get(array.size()-2) + temp2;
                                    array.remove(array.get(array.size()-1));
                                    array.remove(array.get(array.size()-2));
                                    array.add(fullStr);
                                }
                            }*/

                            newSegments.addAll(array);
                        } else {
                            newSegments.add(nextSegment);
                        }
                    }
                }
                allSegments = newSegments;

                // 兜底：结果只有0或1个分段，说明AI基本没分段成功
                if (allSegments.size() <= 1) {
                    log.warn("buildBigSegments: recursive smart segment produced {} segments, falling back to segmentByEmbedding", allSegments.size());
                    List<String> fallbackResult = SegmentTextSupport.segmentByEmbedding(content);
                    if (fallbackResult != null && !fallbackResult.isEmpty()) {
                        return fallbackResult;
                    }
                }

                log.info("buildBigSegments: recursive smart segmentation complete, total {} segments from {} chars",
                        allSegments.size(), contentLength);
                return allSegments;
            } catch (Exception e) {
                log.warn("buildBigSegments: recursive smart segmentation failed, falling back to segmentByEmbedding: {}", e.getMessage());
                List<String> fallbackResult = SegmentTextSupport.segmentByEmbedding(content);
                return fallbackResult != null ? fallbackResult : new ArrayList<>();
            }
        }

        // 分支3：15000字以内，直接调AI分段
        try {
            log.info("buildBigSegments: {} chars <= 15000, direct AI segmentation", contentLength);
            content = content.replace("\n\n", "\n").replace("\n\n\n", "\n").replace("\n\n\n\n", "\n").replaceAll("-\\d+-", "").replaceAll("\\s*—\\s*\\d+\\s*—\\s*", "").toString();
            List<String> result = this.segments(content, docUrl);

            if (result != null) {
                List<String> newData = new ArrayList<>();
                for(int i=0; i<result.size(); i++) {
                    String str = result.get(i);
                    if(str.length() > 1500) {
                        /*
                        List<String> array = this.segments2(str, docUrl);

                        StringBuilder buidler = new StringBuilder();
                        List<String> temp = new ArrayList<>();
                        if(array != null && array.size() > 0) {
                            for(int j=0; j<array.size(); j++) {
                                buidler.append(array.get(j));

                                if(j == array.size() - 1) {
                                    temp.add(buidler.toString());
                                } else  {
                                    if(buidler.length() > 500) {
                                        temp.add(buidler.toString());
                                        buidler = new StringBuilder();
                                    } else {
                                    }
                                }
                            }
                        }*/
                        List<String> temp = this.segments2(str, docUrl);
                        if(temp != null && temp.size() > 0 && temp.size() == 1) {
                            temp = this.segments3(str, docUrl);
                        }

                        newData.addAll(temp);
                    } else {
                        newData.add(str);
                    }
                }
                result = newData;
            }

            if (result != null && result.size() > 1) {
                return result;
            }
            // AI返回结果不理想，用embedding兜底
            log.warn("buildBigSegments: AI returned {} segments, falling back to segmentByEmbedding",
                    result == null ? 0 : result.size());
            List<String> fallbackResult = SegmentTextSupport.segmentByEmbedding(content);
            return fallbackResult != null ? fallbackResult : new ArrayList<>();
        } catch (Exception e) {
            // AI调用异常，用embedding兜底
            log.warn("buildBigSegments: AI exception for short content, falling back to segmentByEmbedding: {}", e.getMessage());
            List<String> fallbackResult = SegmentTextSupport.segmentByEmbedding(content);
            return fallbackResult != null ? fallbackResult : new ArrayList<>();
        }
    }

    /**
     * 主入口方法：对文章内容进行智能分段
     * 采用混合策略：模型返回位置+内容特征，程序通过内容匹配验证位置准确性
     */
    public List<String> segments2(String content, String docUrl) {
        if (content == null || content.trim().isEmpty()) {
            log.warn("Content is empty, return empty segments");
            return new ArrayList<>();
        }

        try {
            /*
            String sysPrompt = "你是一个专业的文档分析助手，擅长对长文本进行语义分段。\n" +
                    "\n" +
                    "任务：以下内容是从一篇文章中提取的一个完整章节（超过2000字），需要你将其在章节内部按语义拆分为多个较短的段落。\n" +
                    "\n" +
                    "分段规则：\n" +
                    "1. 每个分段不超过1500字（允许小幅浮动，但严禁超过1200字）。\n" +
                    "2. 拆分时优先保持小节的完整性：如果章节内有明确的小节标记（如1.1、1.2、2.1等），每个小节应尽量放在同一个分段中，不要将同一小节的内容拆到两个分段。\n" +
                    "3. 如果章节内没有小节标记，则通过语义理解找到话题转换点进行切分，如叙述对象的改变、论点的切换、时间线的跳跃等。\n" +
                    "4. 表格分段规则（非常重要）：\n" +
                    "   - 识别内容中的表格数据（通常是由多行多列组成的结构化数据，包含表头和数据行）。\n" +
                    "   - 同一表格的所有数据必须放在同一个分段中，严禁将一个表格的数据拆到两个分段。\n" +
                    "   - 表格前面如果有描述文字（如\"表1 xxx\"、\"超长表格：xxx\"、\"以下是xxx统计表\"等表格标题或说明），必须将该描述文字与表格数据合并在同一个分段中。\n" +
                    "   - 如果一个表格的内容不超过3000字，则整个表格及其描述文字必须合并在一个分段中。\n" +
                    "   - 如果表格内容非常大（超过3000字），需要拆分为多个分段时，请在表格的行边界处切分（在某一行数据结束后切分，不要在一行中间切分），每个分段尽量接近1500字。\n" +
                    "   - 如果拆分后最后一个表格分段的内容较短（少于500字），严禁将其作为独立分段，也严禁将其放在下一个非表格分段的开头。必须将这部分短表格数据追加到前一个表格分段的末尾（即合并到上一个分段中），即使上一个分段因此超过1500字也允许。\n" +
                    "   - 反例（严禁这样做）：将短表格尾部数据与后面的非表格内容（如新章节标题）放在同一个分段中。例如，表格剩余的几行数据不应和\"八、未来展望\"放在同一分段，而应追加到上一个表格分段的末尾。\n" +
                    "5. 每个分段必须包含完整的句子，严禁在一个句子中间切断。\n" +
                    "6. 每个分段应该是语义完整的，不要出现内容断层或上下文断裂。\n" +
                    "\n" +
                    "输出格式：\n" +
                    "返回JSON对象，必须包含 'segments' 字段，值为对象数组。\n" +
                    "每个对象包含两个字段：\n" +
                    "- position: 起始位置（整数，字符索引从0开始）\n" +
                    "- startText: 起始文本特征（前30-50个字符）\n" +
                    "\n" +
                    "示例：\n" +
                    "假设输入内容为一段4000字的连续文本，按语义应拆分为4个分段：\n" +
                    "应返回：{\n" +
                    "  \"segments\": [\n" +
                    "    {\"position\": 0, \"startText\": \"量子计算是当代计算机科学中\"},\n" +
                    "    {\"position\": 950, \"startText\": \"除了硬件层面的挑战之外\"},\n" +
                    "    {\"position\": 1850, \"startText\": \"在软件和算法层面\"},\n" +
                    "    {\"position\": 2700, \"startText\": \"人才培养同样是量子计算\"}\n" +
                    "  ]\n" +
                    "}\n" +
                    "\n" +
                    "注意事项：\n" +
                    "1. position必须尽量准确，指向分段的第一个字符。\n" +
                    "2. startText用于验证position的准确性。\n" +
                    "3. 返回的JSON格式必须正确，不要包含任何额外文本或markdown标记。\n" +
                    "4. 确保每个分段都是完整的语义单元，避免内容断层。\n" +
                    "5. 不要遗漏任何内容，所有分段拼接起来必须等于原始输入内容。";*/
            String sysPrompt = "你是一个专业的文档分析助手，擅长对长文本进行语义分段。\n" +
                    "\n" +
                    "任务：以下内容是从一篇文章中提取的一个完整章节（超过2000字），需要你将其在章节内部按语义拆分为多个较短的段落。\n" +
                    "\n" +
                    "分段规则：\n" +
                    //"1. 每个分段不超过1500字（允许小幅浮动，但严禁超过1200字），最少也需要超过500字作为一个分段。\n" +
                    "1. 每个分段不超过1000字（允许小幅浮动，但严禁超过1200字）。\n" +
                    //"2. 拆分时优先保持小节的完整性：如果章节内有明确的小节标记（如1.1、1.2、2.1、（一）、（二）、（三）等），每个小节应尽量放在同一个分段中，不要将同一小节的内容拆到两个分段。\n" +
                    "2. 拆分时优先保持小节的完整性：如果章节内有明确的小节标记（如1.1、1.2、2.1等），每个小节应尽量放在同一个分段中，不要将同一小节的内容拆到两个分段。\n" +
                    "3. 如果章节内没有小节标记，则通过语义理解找到话题转换点进行切分，如叙述对象的改变、论点的切换、时间线的跳跃等。\n" +
                    "4. 表格分段规则（非常重要）：\n" +
                    "   - 【最高优先级——纯表格不拆分原则】当输入内容全部是表格数据（表格描述+表头行+数据行，没有正文段落）时，严禁拆分成多个分段，必须将所有内容放在同一个分段中。名称中包含'超长'不代表必须拆分，仍按实际字数判断。对于形式三（竖排单元格表格），如果输入以'【超长表格'或'七、【xxx统计表】'等描述开头、且后续全是短行数据（没有完整的句子或段落），则属于纯表格内容，严禁拆分。\n" +
                    "   - 表格有三种存在形式，都必须识别和处理：\n" +
                    "     形式一（纯文本表格）：没有HTML标签，表现为多行多列的空格分隔数据（每行包含多个字段值），前面通常有描述文字（如'【超长表格：xxx】''表x xxx'等），跨页续表时会出现重复的表头行。特别包括：连续的'文字+空格+文字+空格'模式（如'TEK牌号 化学 类型 颜色 产品描述 粘度'），以及PDF导出的表格内容（如'甲 基 丙 烯 酸 酯'等分散字符形成的表格结构），以及连续的'短字符+换行'模式（如连续5行以上且每行都是1-4个字符的短内容通过换行符分隔，例如：'TEK牌号\\n化学\\n类型\\n颜色\\n产品描述\\n粘度\\n触变性\\n甲\\n基\\n丙\\n烯\\n酸\\n酯'，其中'甲'、'基'、'丙'、'烯'、'酸'、'酯'等都是1-2个字符的短内容，但实际是表格的列名或单元格内容。注意：必须是连续5行以上且每行都是1-4个字符才能识别为换行分隔表格，避免将正常段落的自然换行误判为表格。一旦识别出换行分隔表格，其前后所有相关内容（包括表格的所有行列和相邻的短行数据）都必须完整地放在同一个分段中，不得在表格中间任何位置创建分段边界）。\n" +
                    "     形式二（HTML表格）：有明确的 <table> 到 </table> 标签。\n" +
                    "     形式三（竖排单元格表格）：PDF或图片提取后，表格的每个单元格内容独占一行（如'城市\n名称\n国\n家\n所\n属\n洲\n39.9\n116.4'），看起来像大量短行文本，但实际是结构化表格数据。前面通常有描述文字（如'【超长表格：xxx】''七、【xxx统计表】'等）作为识别标志。\n" +
                    "   - 【页码标记处理】文档中可能出现页码标记（如'— 11 —''— 12 —'或'- 11 -'等由破折号/短横线+数字+破折号/短横线组成的标记），这些不是内容也不是分段边界，识别到此类标记时必须忽略，不得在其前后创建分段边界。\n" +
                    "   - 【核心规则——表格完整性阈值：3000字】\n" +
                    "     a. 当一个表格（包括其前面的描述文字、表格数据内容本身、后面的备注文字）的总字数不超过3000字时，整个表格必须放在同一个分段中，严禁拆分成多个分段。即使该分段因此超过常规上限也允许，以保证表格数据的完整性。\n" +
                    "     b. 如果表格总字数略超3000字（如3000-4000字之间），仍应优先合并为一个分段，仅在显著超过4000字时才需要按行切分。\n" +
                    "     c. 当表格确实需要切分时（显著超过4000字），必须在表格的行边界处切分（在某一行数据完整结束后切分，严禁在一行数据中间切断）。对于HTML表格，切分点必须在 </tr> 之后。切分后如果最后一个分段较短（少于500字），必须将其合并到上一个表格分段中。\n" +
                    "     d. 【严禁把表格描述单独成段】表格描述（如'【超长表格：xxx】''专栏：xxx'等）是表格的组成部分，必须紧跟表格数据放在同一个分段中，严禁将描述单独作为一个分段。\n" +
                    "   - 表格前面如果有描述文字（如'表1 xxx''超长表格：xxx''专栏：xxx'等），该描述必须与表格合并在同一个分段中，严禁在描述和表格之间创建分段边界。\n" +
                    "   - 表格后面如果有以'注：''备注：'开头的备注文字，该备注必须与表格合并在同一个分段中。\n" +
                    "   - 【表格后章节标题处理】表格后面如果出现明确的章节或小节标题（以'一、''二、''（一）''（二）''1.''2.''（1）''#'等编号符号开头），应识别为新章节或小节的开始，作为新分段的起点，严禁与表格合并在同一个分段。\n" +
                    "   - 对于纯文本表格（形式一）和竖排单元格表格（形式三），跨页续表的重复表头行（相同表头内容重复出现）属于同一张表的数据，严禁在重复表头行处创建分段边界。所有页的数据必须合并到同一个分段中（除非表格显著超过4000字需要按行切分）。\n" +
                    "   - 对于HTML表格（形式二），一个完整的表格单元由三部分组成：表格描述（紧邻<table>之前的文字）、表格内容（<table>到</table>）、表格备注（紧邻</table>之后以'注'或'备注'开头的文字），严禁在三者之间创建分段边界。\n" +
                    "   - 反例（严禁这样做）：将短表格尾部数据与后面的非表格内容（如新章节标题）放在同一个分段中。例如，表格剩余的几行数据不应和'八、未来展望'放在同一分段，而应追加到上一个表格分段中。\n" +
                    "5. 每个分段必须包含完整的句子，严禁在一个句子中间切断。\n" +
                    "6. 每个分段应该是语义完整的，不要出现内容断层或上下文断裂。\n" +
                    "7. 拆分的时候需要注意，分段如果遇到（一、二、三）类似这样的章节开始，一定要以（一、二、三）作为分段开始。\n" +
                    "8. 小于100字的分段请在章节内部进行合并，合并的规则。合并规则：如果当前分段是章节或小节的最后，请和前一分段合并，如果当前分段是章节的开始，请向后一分段合并。建议每个分段控制在500-1200字\n" +
                    "9. 大段落分段策略：如果分段中文字比较多，超过1500字请按小节进行拆分，比如需要去找一些关键字，比如（1.1、1.2、2.1，（一）、（二）、（三），（1），（2），（3）），拆分的时候注意，如果单个小节文字比较少，请向上合并，确保每个分段控制在500-1200字\n" +
                    "10. 小段落分段策略：" +
                    " - 如果当前分段识别是目录，目录的文字相对比较少，请向上合并，确保每个分段控制在500-1200字\n" +
                    " - 如果当前分段识别章节开始，类似遇到（一、二、三）类似这样的章节开始的标识，一定要以（一、二、三）作为分段开始， 如果文字比较少，一定少向下合并。确保每个分段控制在500-1200字。\n" +
                    "11. 如果遇到分段中包含表格和长文本，请把表格和其他文本单独进行分段，表格作为单独一个分段，表格分段的时候请一定带上前面的表格说明。\n" +
                    "12. 如果小节的描述分段内容比较短少于120个字，请向下合并，确保每个分段控制在500-1200字，小节的描述分段可以通过（1.1、1.2、2.1，（一）、（二）、（三），（1），（2），（3））这些特殊标识来识别\n" +
                    "13. 【标题绑定与连续标题合并——最高优先级，违反即判定为错误】\n" +
                    "    a. 标题绝不与正文分离：任何分段如果以标题开头（标题特征：以「#」开头，或以「一、」「二、」「三、」「（一）」「（二）」「（三）」「第一章」「第二章」等章节编号开头），则该标题以及紧随其后的所有正文内容必须放在同一个分段里。严禁在标题和它紧跟的正文之间创建分段边界，严禁产生“只含标题、不含正文”的分段。\n" +
                    "    b. 连续标题必须合并进同一分段：当某个标题后面紧跟的仍然是另一个标题（两个标题之间没有任何正文段落），这两个标题以及它们之后的第一段正文必须全部放在同一个分段里，严禁在相邻的两个标题之间创建分段边界。\n" +
                    "    c. 必须照做的例子（一）：输入内容以「# 第一章 圆满完成小康社会建设目标 # “十四五”期间经济社会总体情况 面对错综复杂的……（正文）」开头时，正确输出是一个分段（position=0，以「# 第一章」开头），它包含「# “十四五”期间经济社会总体情况」和后面所有的正文；严禁把「# 第一章……# “十四五”……」单独切成一个分段、又把「面对错综复杂……」正文单独切成另一个分段。\n" +
                    "    d. 必须照做的例子（二）：输入内容以「# 一、加速三个主导产业集群扩张 # （一）稀土产业 适应……（正文）」开头时，正确输出是一个分段（position=0，以「# 一、加速三个主导产业集群扩张」开头），它包含「# （一）稀土产业」和后面所有的正文；严禁把「# 一、加速三个主导产业集群扩张」单独切成一个分段、又把「# （一）稀土产业 适应……」切成另一个分段。\n" +
                    "\n" +
                    "输出格式：\n" +
                    "返回JSON对象，必须包含 'segments' 字段，值为对象数组。\n" +
                    "每个对象包含两个字段：\n" +
                    "- position: 起始位置（整数，字符索引从0开始）\n" +
                    "- startText: 起始文本特征（前30-50个字符）\n" +
                    "\n" +
                    "示例：\n" +
                    "假设输入内容为一段4000字的连续文本，按语义应拆分为4个分段：\n" +
                    "应返回：{\n" +
                    "  \"segments\": [\n" +
                    "    {\"position\": 0, \"startText\": \"量子计算是当代计算机科学中\"},\n" +
                    "    {\"position\": 950, \"startText\": \"除了硬件层面的挑战之外\"},\n" +
                    "    {\"position\": 1850, \"startText\": \"在软件和算法层面\"},\n" +
                    "    {\"position\": 2700, \"startText\": \"人才培养同样是量子计算\"}\n" +
                    "  ]\n" +
                    "}\n" +
                    "\n" +
                    "注意事项：\n" +
                    "1. position必须尽量准确，指向分段的第一个字符。\n" +
                    "2. startText用于验证position的准确性。\n" +
                    "3. 返回的JSON格式必须正确，不要包含任何额外文本或markdown标记。\n" +
                    "4. 确保每个分段都是完整的语义单元，避免内容断层。\n" +
                    "5. 不要遗漏任何内容，所有分段拼接起来必须等于原始输入内容。";
            String userPrompt = buildUserPrompt(content);

            log.info("Calling LLM for semantic segmentation with fuzzy matching strategy");

            SmartParsingTopicGenDto result = null;
            try {
                result = modelApplicationService.call(sysPrompt, userPrompt.toString(), new ParameterizedTypeReference<SmartParsingTopicGenDto>() {
                });
            } catch (Exception e) {
                e.printStackTrace();
                //System.out.println(userPrompt.length());
                //System.out.println(userPrompt.toString());
                result = modelApplicationService.call(sysPrompt, userPrompt.toString(), new ParameterizedTypeReference<SmartParsingTopicGenDto>() {
                });
            }


            if (result == null || result.getSegments()  == null ) {
                log.warn("LLM result is null or missing 'segments' key");
                return SegmentTextSupport.fallbackSegmentation(content);
            }

            // 🎯 使用模糊匹配策略解析分段（保留完整的模糊匹配逻辑）
            List<String> segments = SegmentPositionMatcher.parseSegmentsUsingFuzzyStrategy(result, content);



            if (segments.isEmpty()) {
                log.warn("No segments produced, using fallback");
                return SegmentTextSupport.fallbackSegmentation(content);
            }

            // 合并短段（小于500字）
            List<String> mergedSegments = segments;

            log.info("Successfully produced {} segments after merging", mergedSegments.size());
            return mergedSegments;

        } catch (Exception e) {
            log.error("Error during LLM segmentation, using fallback method", e);
            return SegmentTextSupport.fallbackSegmentation(content);
        }
    }

    public List<String> segments3(String content, String docUrl) {
        if (content == null || content.trim().isEmpty()) {
            log.warn("Content is empty, return empty segments");
            return new ArrayList<>();
        }

        try {
            String sysPrompt = "你是一名专业的长文档语义分段助手。把给定的一篇文章章节内容（可能包含正文和HTML表格）切成若干个语义完整的分段。要求'分段尽量少、每段尽量饱满'，而不是切得越细越好。\n" +
                    "\n" +
                    "【标题层级】（从大到小）\n" +
                    "- 大节标题：如（一）（二）（三）……\n" +
                    "- 一级标题：如 1. 2. 3. 4. ……（'数字+点'开头）\n" +
                    "- 二级标题：如（1）（2）（3）……\n" +
                    "- 表格说明：如'专栏：xxx''表x xxx''以下是xxx统计表'等，其后紧跟 <table> 标签的 HTML 表格。\n" +
                    "\n" +
                    "【分段规则】\n" +
                    "1. 分段目标长度：每个分段 500-1200 字（尽量接近1000字），分段数量尽量少。不要在每个二级标题处都切分——只有当累计内容接近或超过1000字、再加入下一个小节会明显超过1200字时，才在该标题边界切分。\n" +
                    "2. 优先按'一级标题（1./2./3./4.）或大节标题'聚合：把一个一级标题下的多个二级标题（1）（2）…合并进同一个分段；只有当某个一级标题下的内容累计超过1200字时，才在其中某个二级标题的边界切分，且切分后每段仍须 >=500字。\n" +
                    "   【长文本按一级标题切分——此规则优先级高于规则1的长度要求】当某一大节（如“（二）xxx”）下非表格正文总量超过1000字、且含多个一级标题（1. 2. 3. 4.）时，必须按以下方式切分：\n" +
                    "   a. 大节标题（如“（二）xxx”）及其引言段落（即第一个一级标题之前的正文）构成第一个分段。\n" +
                    "   b. 从第一个一级标题开始，每个一级标题（1. 2. 3. 4.）及其下属二级标题（1）（2）等内容各构成一个独立分段。\n" +
                    "   c. 即使切分后个别分段不足500字，也必须按此规则切分。严禁将相邻的两个一级标题合并到同一分段（例如不得将“3.”和“4.”合并在同一段）。\n" +
                    "   d. 仅当某个一级标题分段不足200字时，才将该短段向上合并到前一个分段。\n" +
                    "3. 切分边界必须落在'标题行的开头'（大节标题/一级标题/二级标题），严禁在句子中间或某小节正文中切断。每个分段的开头应是一个标题或紧接上一段的完整句子。\n" +
                    "   【最高优先级】任何大标题（如'一、''二、''三、'）或小标题（如'（一）''（二）''（三）'）都必须作为新分段的开始点。严禁把大标题或小标题拼到上一个分段的末尾（尤其是表格 </table> 之后紧跟的标题，必须作为下一段的开头）。\n" +
                    "   【标题不可孤立】标题不得单独成为一个只含标题、不含正文的分段，也不得向后拼到上一个分段末尾。正确处理方式：标题必须作为新分段的开始点（在该标题处创建切分边界），然后将其后续正文内容并入该分段。如果某标题后面紧跟另一个标题（中间无正文段落），则在该标题处创建切分边界，前一个标题作为后一个标题所在分段的开头。例如“（二）纺织服装产业”后紧跟“1. xxx”时，切分边界应在“（二）”处，“（二）”和“1. xxx”及其后续正文合并在同一分段中。\n" +
                    "4. 严禁把'上一个一级标题'的某个二级标题内容并到'下一个一级标题'的分段里；分段内部的二级标题必须从属于其上方的一级标题。\n" +
                    "5. 若某个分段很短（少于150字），向'下'合并到后续内容，保证每段在500-1200字；但合并不得跨越到下一个大标题。\n" +
                    "6. 表格分段规则（最高优先级，必须严格遵守）：\n" +
                    "   - 【最高优先级——纯表格不拆分原则】当输入内容全部是表格数据（表格描述+表头行+数据行，没有正文段落）时，严禁拆分成多个分段，必须将所有内容放在同一个分段中。名称中包含'超长'不代表必须拆分，仍按实际字数判断。对于形式三（竖排单元格表格），如果输入以'【超长表格'或'七、【xxx统计表】'等描述开头、且后续全是短行数据（没有完整的句子或段落），则属于纯表格内容，严禁拆分。\n" +
                    "   - 表格有三种存在形式，都必须识别和处理：\n" +
                    "     形式一（纯文本表格）：没有HTML标签，表现为多行多列的空格分隔数据（每行包含多个字段值），前面通常有描述文字（如'【超长表格：xxx】''表x xxx'等），跨页续表时会出现重复的表头行。特别包括：连续的'文字+空格+文字+空格'模式（如'TEK牌号 化学 类型 颜色 产品描述 粘度'），以及PDF导出的表格内容（如'甲 基 丙 烯 酸 酯'等分散字符形成的表格结构），以及连续的'短字符+换行'模式（如连续5行以上且每行都是1-4个字符的短内容通过换行符分隔，例如：'TEK牌号\\n化学\\n类型\\n颜色\\n产品描述\\n粘度\\n触变性\\n甲\\n基\\n丙\\n烯\\n酸\\n酯'，其中'甲'、'基'、'丙'、'烯'、'酸'、'酯'等都是1-2个字符的短内容，但实际是表格的列名或单元格内容。注意：必须是连续5行以上且每行都是1-4个字符才能识别为换行分隔表格，避免将正常段落的自然换行误判为表格。一旦识别出换行分隔表格，其前后所有相关内容（包括表格的所有行列和相邻的短行数据）都必须完整地放在同一个分段中，不得在表格中间任何位置创建分段边界）。\n" +
                    "     形式二（HTML表格）：有明确的 <table> 到 </table> 标签。\n" +
                    "     形式三（竖排单元格表格）：PDF或图片提取后，表格的每个单元格内容独占一行（如'城市\n名称\n国\n家\n39.9\n116.4'），看起来像大量短行文本，但实际是结构化表格数据。前面通常有描述文字（如'【超长表格：xxx】''七、【xxx统计表】'等）作为识别标志。\n" +
                    "   - 【页码标记处理】文档中可能出现页码标记（如'— 11 —''— 12 —'或'- 11 -'等由破折号/短横线+数字+破折号/短横线组成的标记），这些不是内容也不是分段边界，识别到此类标记时必须忽略，不得在其前后创建分段边界。\n" +
                    "   - 【核心规则——表格完整性阈值：3000字】\n" +
                    "     a. 当一个表格（包括其前面的描述文字、表格数据内容本身、后面的备注文字）的总字数不超过3000字时，整个表格必须放在同一个分段中，严禁拆分成多个分段。即使该分段因此超过常规上限也允许，以保证表格数据的完整性。\n" +
                    "     b. 如果表格总字数略超3000字（如3000-4000字之间），仍应优先合并为一个分段，仅在显著超过4000字时才需要按行切分。\n" +
                    "     c. 当表格确实需要切分时（显著超过4000字），必须在表格的行边界处切分（在某一行数据完整结束后切分，严禁在一行数据中间切断）。对于HTML表格，切分点必须在 </tr> 之后。切分后如果最后一个分段较短（少于500字），必须将其合并到上一个表格分段中。\n" +
                    "     d. 【严禁把表格描述单独成段】表格描述（如'【超长表格：xxx】''专栏：xxx'等）是表格的组成部分，必须紧跟表格数据放在同一个分段中，严禁将描述单独作为一个分段。\n" +
                    "   - 表格前面如果有描述文字（如'表1 xxx''超长表格：xxx''专栏：xxx'等），该描述必须与表格合并在同一个分段中，严禁在描述和表格之间创建分段边界。\n" +
                    "   - 表格后面如果有以'注：''备注：'开头的备注文字，该备注必须与表格合并在同一个分段中。\n" +
                    "   - 【表格后章节标题处理】表格后面如果出现明确的章节或小节标题（以'一、''二、''（一）''（二）''1.''2.''（1）''#'等编号符号开头），应识别为新章节或小节的开始，作为新分段的起点，严禁与表格合并在同一个分段。\n" +
                    "   - 对于纯文本表格（形式一）和竖排单元格表格（形式三），跨页续表的重复表头行（相同表头内容重复出现）属于同一张表的数据，严禁在重复表头行处创建分段边界。所有页的数据必须合并到同一个分段中（除非表格显著超过4000字需要按行切分）。\n" +
                    "   - 对于HTML表格（形式二），一个完整的表格单元由三部分组成：表格描述（紧邻<table>之前的文字）、表格内容（<table>到</table>）、表格备注（紧邻</table>之后以'注'或'备注'开头的文字），严禁在三者之间创建分段边界。<table> 标签必须始终作为一个新分段的开始点。严禁把 <table> 标签或表格的任何部分拼到上一个正文分段的末尾。一个完整的 <table> 到 </table> 必须完整地放在同一个分段中，严禁从表格中间切断。\n" +
                    "   - 反例（严禁这样做）：把'专栏：xxx'留在上一正文分段末尾，导致表格说明和表格分到两段。正确做法：'专栏：xxx'和 <table>...</table> 必须在同一段。将短表格尾部数据与后面的非表格内容（如新章节标题）放在同一个分段中也是严禁的。\n" +
                    "7. 每个分段必须包含完整句子，分段必须语义完整、无内容断层。\n" +
                    "8. 所有分段按顺序拼接后必须等于原始输入，不得遗漏或重复任何字符。\n" +
                    "9. 第一个分段的 position 必须为 0。\n" +
                    "\n" +
                    "【输出格式】\n" +
                    "仅返回JSON对象（不要markdown代码块、不要任何多余文字）：\n" +
                    "{\n" +
                    "  'segments': [\n" +
                    "    {'position': 0, 'startText': '（二）特色现代农业'},\n" +
                    "    {'position': 520, 'startText': '2.完善特色现代农业产业体系'}\n" +
                    "  ]\n" +
                    "}\n" +
                    "position=该分段第一个字符在原文中的索引（从0开始的整数）；startText=该分段开头30-50字的真实文本，必须与原文逐字一致，用于校验position。\n";
            String userPrompt = buildUserPrompt(content);

            log.info("Calling LLM for semantic segmentation with fuzzy matching strategy");

            SmartParsingTopicGenDto result = null;
            try {
                result = modelApplicationService.call(sysPrompt, userPrompt.toString(), new ParameterizedTypeReference<SmartParsingTopicGenDto>() {
                });
            } catch (Exception e) {
                e.printStackTrace();
                //System.out.println(userPrompt.length());
                //System.out.println(userPrompt.toString());
                result = modelApplicationService.call(sysPrompt, userPrompt.toString(), new ParameterizedTypeReference<SmartParsingTopicGenDto>() {
                });
            }


            if (result == null || result.getSegments()  == null ) {
                log.warn("LLM result is null or missing 'segments' key");
                return SegmentTextSupport.fallbackSegmentation(content);
            }

            // 🎯 使用模糊匹配策略解析分段（保留完整的模糊匹配逻辑）
            List<String> segments = SegmentPositionMatcher.parseSegmentsUsingFuzzyStrategy(result, content);



            if (segments.isEmpty()) {
                log.warn("No segments produced, using fallback");
                return SegmentTextSupport.fallbackSegmentation(content);
            }

            // 合并短段（小于500字）
            //List<String> mergedSegments = SegmentTextSupport.mergeShortSegments(segments, 500);
            List<String> mergedSegments = segments;

            log.info("Successfully produced {} segments after merging", mergedSegments.size());
            return mergedSegments;

        } catch (Exception e) {
            log.error("Error during LLM segmentation, using fallback method", e);
            return SegmentTextSupport.fallbackSegmentation(content);
        }
    }

    public List<String> segments(String content, String docUrl) {
        if (content == null || content.trim().isEmpty()) {
            log.warn("Content is empty, return empty segments");
            return new ArrayList<>();
        }

        int originalLength = content.length();
        log.info("Starting smart segmentation with fuzzy range + content matching, content length: {}", originalLength);

        // 内容长度限制处理：超长文本分片处理，每片独立调AI再合并
        String truncatedContent = content;
        int maxContentLength = 30000;
        boolean wasTruncated = false;
        if (content.length() > maxContentLength) {
            // For very long content, use local chunking instead of AI
            // AI truncation loses too much information
            log.info("Content too long for single AI call ({} chars), using chunked approach", content.length());
            List<String> chunked = new ArrayList<>();
            int chunkSize = 500;
            StringBuilder chunkBuf = new StringBuilder();
            // Split by sentences first, group into ~500 char chunks
            String[] sentences = content.split("(?<=[。！.?])");
            for (String sentence : sentences) {
                if (chunkBuf.length() + sentence.length() > chunkSize && chunkBuf.length() > 0) {
                    chunked.add(chunkBuf.toString().trim());
                    chunkBuf = new StringBuilder();
                }
                chunkBuf.append(sentence);
            }
            if (chunkBuf.length() > 0) {
                chunked.add(chunkBuf.toString().trim());
            }
            if (chunked.size() > 1) {
                return chunked;
            }
            // If sentence split didn't work (no punctuation), fall through to AI with truncation
            truncatedContent = content.substring(0, maxContentLength);
            wasTruncated = true;
            log.info("Falling back to AI with truncated content ({} chars)", maxContentLength);
        }

        try {
            // 🎯 核心修改：使用模糊匹配策略（不再需要预处理和位置映射）
            // 直接使用原始文本调用LLM
            String sysPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(truncatedContent);

            log.info("Calling LLM for semantic segmentation with fuzzy matching strategy");

            /*
            userPrompt = "先阅读一下https://weapp.tfswufe.edu.cn/itfer-cjczzdy/public/1.pdf这篇文章，然后将文档的内容进行分段";
            sysPrompt = "你是一个专业的文档分析助手，擅长理解文章的章节结构和语义逻辑。\n" +
                    "        任务：分析以下文档地址里面的内容，按照章节语义将其划分为完整的语义段落。\n" +
                    "        章节语义划分要求：\n" +
                    "        1. 先阅读下https://weapp.tfswufe.edu.cn/itfer-cjczzdy/public/1.pdf这篇文章 \n" +
                    "        2. 识别文章的主要章节结构，如'一、二、三'、'第一章、第二章'、'1.1、1.2'等明显的章节标记。\n" +
                    "        3. 理解每个章节的语义范围，从章节标题开始到下一个章节标题前的所有内容。\n" +
                    "        4. 每个分段应该包含完整的语义内容，确保上下文连贯，不出现内容断层。\n" +
                    "        5. 不要在段落中间或句子中间进行切分。\n" +
                    "        6. 保持章节内部的逻辑完整性和连贯性。\n" +
                    "        7. 自然段为最小单位，不要在自然段内部进行分段，自然段内部分段会破坏文章的语意的完整性，自然段可以通过文章中的特殊符号进行划分比如：双换行符等。\n" +
                    "        8. 针对一些非常短的自然段大概8个字以内，也可以作为文章章节的标记。\n" +
                    "        9. 如果划分的单个章节的文字过多，比如单个分段超过1500字，看能不能按照语意单独拆分成多个分段。\n" +
                    "        输出要求：\n" +
                    "        1. 返回每个分段的完整内容，保持原文的所有文字。\n" +
                    "        2. 同时返回每个分段在原文中的起始位置（字符索引，从0开始）。\n" +
                    "        3. 返回每个分段起始位置的文本特征（前30-50个字符），用于位置验证。\n" +
                    "        4. 确保分段内容语义完整，不要遗漏任何内容。\n" +
                    "        输出格式：\n" +
                    "        返回JSON对象，必须包含 'segments' 字段，值为对象数组。\n" +
                    "        每个对象包含三个字段：\n" +
                    "        - position: 起始位置（整数）\n" +
                    "        - startText: 起始文本特征（前30-50个字符）\n" +
                    "        - content: 完整的分段内容\n" +
                    "        示例：\n" +
                    "        原文：'一、引言\\\\n这是引言内容。\\\\n\\\\n二、方法\\\\n这是方法内容。\n" +
                    "        应返回：{\n" +
                    "          \"segments\": [\n" +
                    "            {\"position\": 0, \"startText\": \"一、引言\"},\n" +
                    "            {\"position\": 12, \"startText\": \"二、方法\"}\n" +
                    "          ]\n" +
                    "        }\n" +
                    "        注意事项：\n" +
                    "        1. position必须尽量准确，指向章节标题的第一个字符。\n" +
                    "        2. startText用于验证position的准确性。\n" +
                    "        3. content必须包含该章节的完整内容，不能遗漏。\n" +
                    "        4. 返回的JSON格式必须正确，不要包含任何额外文本或markdown标记。\n" +
                    "        5. 确保每个分段都是完整的语义单元，避免内容断层。\n" +
                    "        文档地址:\n" +
                    "           https://weapp.tfswufe.edu.cn/itfer-cjczzdy/public/1.pdf";*/
            /*
            Map<String, String> result = modelApplicationService.call(
                    sysPrompt,
                    userPrompt,
                    new org.springframework.core.ParameterizedTypeReference<Map<String, String>>() {}
            );*/
            //SmartParsingTopicGenDto
            /*
            Map<String, String> result = modelApplicationService.call(
                    sysPrompt,
                    userPrompt,
                    new org.springframework.core.ParameterizedTypeReference<Map<String, String>>() {}
            );*/

            SmartParsingTopicGenDto result = null;
            try {
                result = modelApplicationService.call(sysPrompt, userPrompt.toString(), new ParameterizedTypeReference<SmartParsingTopicGenDto>() {
                });
            } catch (Exception e) {
                e.printStackTrace();
                result = modelApplicationService.call(sysPrompt, userPrompt.toString(), new ParameterizedTypeReference<SmartParsingTopicGenDto>() {
                });
            }

            // 解析模型返回结果
            /*
            if (result == null || !result.containsKey("segments")) {
                log.warn("LLM result is null or missing 'segments' key");
                return SegmentTextSupport.fallbackSegmentation(content);
            }

            String jsonResult = result.get("segments");*/
            if (result == null || result.getSegments()  == null ) {
                log.warn("LLM result is null or missing 'segments' key");
                return SegmentTextSupport.fallbackSegmentation(content);
            }

            // 🎯 使用模糊匹配策略解析分段（保留完整的模糊匹配逻辑）
            List<String> segments = SegmentPositionMatcher.parseSegmentsUsingFuzzyStrategy(result, truncatedContent);

            /*
            boolean needReplayState = false;
            if(segments != null && segments.size() > 0) {
                for(int i=0; i<segments.size()-1; i++) {
                    if(segments.size() > 1500) {
                        needReplayState = true;
                    }
                }
            }

            if(needReplayState == true) {
                System.out.println("xxx===================1=2");
            }*/

            if (segments.isEmpty()) {
                log.warn("No segments produced, using fallback");
                return SegmentTextSupport.fallbackSegmentation(content);
            }

            // 合并短段（小于500字）
            //List<String> mergedSegments = SegmentTextSupport.mergeShortSegments(segments, 500);
            List<String> mergedSegments = segments;

            log.info("Successfully produced {} segments after merging", mergedSegments.size());
            return mergedSegments;

        } catch (Exception e) {
            log.error("Error during LLM segmentation, using fallback method", e);
            return SegmentTextSupport.fallbackSegmentation(content);
        }
    }

    /**
     * 构建系统提示词 - 混合策略：返回位置+内容特征+完整内容
     */
    private String buildSystemPrompt() {
        return "你是一个专业的文档分析助手，擅长理解文章的章节结构和语义逻辑。\n" +
                "\n" +
                "任务：分析以下文章内容，将其划分为完整的语义段落。\n" +
                "\n" +
                "分段策略（按优先级）：\n" +
                "策略一（有章节标记的文档）：\n" +
                "1. 识别文章的章节结构与层级关系，明确以下层级从属关系（从高到低）：\n" +
                "   - 顶级（主章节）：'第X章'、'第X篇'、'一、二、三、四、五、六、七、八'、'第一章、第二章、第三章'等主章节标记。\n" +
                "   - 二级（节）：'第X节'、'第一节、第二节、第三节'——这是主章节下的子节，不是独立章节。\n" +
                "   - 三级（条）：'第X条'、'第七条、第八条、第九条'——这是节下的子条，不是独立章节。\n" +
                "   - 四级（数字编号）：'1. 2. 3. 4.'——是条下的具体条目。\n" +
                "   - 五级（括号编号）：'（1）（2）（3）'——是条目下的细分项。\n" +
                "   也包括数字小节标记（如'1.1、1.2'）以及Markdown标题语法（通过'#'区分层级）。\n" +
                "\n" +
                "   【强制规则】：二级及以下的所有标记（包括但不限于'第一节、第二节、第七条、第八条、1. 2. 3.、（1）（2）'）都是主章节的子级内容，严禁将它们识别为新的章节起点。它们必须归入所属的主章节分段中。\n" +
                "2. 以章或大节为基本分段单位，将章节标题及其下属的所有小节内容合并在同一个分段中。从章节标题开始到下一个同级或更高级章节标题前的所有内容都属于同一分段。\n" +
                "   章节边界规则（强制）：遇到新的章节标题（如\"第X章\"、\"第三章\"、\"一、\"、\"、二\"、\"、三\"）时，必须在该标题处立即断开并开始新分段。严禁将前一章的尾部内容（如前一章的最后一条）带入新章节的分段中。\n" +
                "3. 【最高优先级】章节标题必须与第一个小节合并规则（必须严格遵守，优先级高于其他所有规则）：\n" +
                "   - 章节标题严禁单独成段：主章节标题（如\"第三章 成果等级划分\"）必须与紧跟其后的第一个子节（如\"第一节\"）合并在同一个分段中，无论内容多长。\n" +
                "   - \"第一节、第二节\"等节标题必须和前面的主章节标题（如\"第三章\"）合并在同一分段中，严禁单独分段。\n" +
                "   - \"第七条、第八条\"等条标题必须和前面的节标题（如\"第一节\"）及主章节标题合并在同一分段中，严禁单独分段。\n" +
                "   - \"1. 2. 3.\"等数字编号必须和前面的条标题合并在同一分段中，严禁单独分段。\n" +
                "   - \"（1）（2）\"等括号编号必须和前面的数字编号合并在同一分段中，严禁单独分段。\n" +
                "\n" +
                "   - 合并原则：只要总字数不超过1000字，就必须将主章节及其所有子级内容（节、条、编号）合并为一个分段。这是强制要求，不是可选建议。\n" +
                "   - 即使子级标题与主章节标题之间没有空行，也必须合并。\n" +
                "   - 即使子级中包含表格，只要合并后总长度不超过1000字，就必须合并到主章节分段中。\n" +
                "   - 禁止任何形式的单独分段：严禁将\"第三章\"单独分段，严禁将\"第一节\"单独分段，严禁将\"第七条\"单独分段。\n" +
                "   - 章节末尾短内容向上合并：如果某章节的最后一小段内容（如最后一条\"第X条\"）非常短（少于200字），必须将该短内容向上合并到同章节的前一个分段中，严禁跨章节向后合并到下一章的分段中。\n" +
                "4. 大章节拆分规则（仅适用于超过1000字的大章节）：\n" +
                "   - 只有当一个主章节的总内容（包括其所有子级内容）超过1000字时，才考虑拆分。\n" +
                "   - 拆分必须在子级边界处进行：首选在节边界处拆分（如第一节结束、第二节开始），其次在条边界处拆分（如第七条结束、第八条开始），保持每个子级内容的完整性。\n" +
                "   - 拆分时每个分段目标长度约1000字左右，允许在800-1200字之间浮动。\n" +
                "   - 拆分后的每个分段必须是完整的语义单元，严禁在一个句子中间切断。\n" +
                "   - 拆分后的每个分段必须保持表格完整，严禁将一个表格拆到两个分段中。\n" +
                "   - 1000字以内的章节（包括所有子级内容）绝对不需要拆分，必须全部合为一段。\n" +
                "   - 拆分时优先保持子级内容完整性，如果小节内容本身已经超过1200字，可以在该小节内部按语义进行二次拆分。\n" +
                "5、如果当前分段识别是目录，目录的文字相对比较少，请向上合并，确保每个分段控制在500-1200字。\n " +
                "6、短章节合并规则（本规则优先于上述“章节边界规则”，是断开规则的例外）：\n" +
                "   - 当某个分段以章节标识开头（章节标识包括：以「#」开头的markdown标题、以「一、二、三、」开头的中文编号、以「（一）（二）（三）」开头的括号编号、以「第X章」开头的章节标记），并且该分段的内容非常少（总字数少于100字，通常只是一个孤立的章节标题、标题后面没有任何正文段落内容）；\n" +
                "   - 同时，紧随其后的下一个分段也以章节标识开头（即下一个分段也是新章节的开始）；\n" +
                "   - 这种情况下，严禁在这两个章节之间创建分段边界，必须将这个短章节分段与紧随其后的下一个章节分段合并为同一个分段，合并后的分段以原来那个短章节标题开头。\n" +
                "   - 必须照做的例子：输入内容中有「# 一、加速三个主导产业集群扩张」（只有标题、不足100字），紧接着是「# （一）稀土产业 适应……（正文）」，则严禁在它们之间分段，必须合并为同一个分段，该分段以「# 一、加速三个主导产业集群扩张」开头。\n" +
                "   - 严禁的做法：把「# 一、加速三个主导产业集群扩张」单独切成一个分段、又把「# （一）稀土产业」切成另一个分段。\n" +
                "\n" +
                "策略二（无章节标记的文档，如纯文本、无标点文本）：\n" +
                "1. 通过语义理解识别文章中的话题转换点，如叙述对象的改变、时间线的跳跃、论点的切换等。\n" +
                "2. 在语义转换处进行分段，确保每个分段围绕一个相对完整的话题或叙述单元。\n" +
                "3. 每个分段目标长度约1000字左右，允许在800-1200字之间浮动，以语义完整性为优先。\n" +
                "4. 即使文本没有标点符号或段落标记，也要通过语义分析找到合适的分段位置。\n" +
                "\n" +
                "通用规则：\n" +
                "1. 每个分段应该包含完整的语义内容，确保上下文连贯，不出现内容断层。\n" +
                "2. 不要在句子中间进行切分。\n" +
                "3. 保持段落内部的逻辑完整性和连贯性。\n" +
                "4. 针对一些非常短的自然段大概8个字以内，也可以作为分段标记。\n" +
                "5. 文章中的表格必须保持完整，将整个表格放在同一个分段中，严禁在表格中间切分。如果表格前后有紧密关联的说明文字，也应一并纳入该分段。\n" +
                "6. 【表格完整性强制规则】（最高优先级，必须严格遵守）：\n" +
                "   a. 表格识别：当检测到表格开始标记（如连续包含'|'符号的行、Markdown表格语法、或明显的表格结构）时，必须立即启动表格完整性保护模式。\n" +
                "   b. 表格边界识别：表格的开始通常表现为：\n" +
                "      - 连续2行以上包含'|'符号的行\n" +
                "      - 包含表头分隔符的行（如'|---|---|'）\n" +
                "      - Markdown表格语法（'| 列1 | 列2 |'格式）\n" +
                "      - HTML表格标签（'<table>'、'<tr>'、'<td>'等）\n" +
                "      - 空格分隔的表格格式：连续的'文字+空格+文字+空格'模式（如'TEK牌号 化学 类型 颜色 产品描述 粘度'），特别是当一行中包含多个用空格分隔的词语且明显是表头时\n" +
                "      - PDF导出的表格内容：连续多行每行都包含类似的'文字+空格+文字+空格'模式，形成表格结构（如'甲 基 丙 烯 酸 酯'等分散字符）\n" +
                "      - 换行分隔的表格格式：连续5行以上的'短字符+换行符+短字符+换行符'模式（如'TEK牌号\\n化学\\n类型\\n颜色\\n产品描述\\n粘度'），其中每行都是1-4个字符的短内容，这通常是PDF导出的表格列名或单元格内容（如'甲\\n基\\n丙\\n烯\\n酸\\n酯'都是1-2个字符）。注意：必须是连续5行以上且每行都是1-4个字符才能识别为换行分隔表格，避免将正常段落的自然换行误判为表格。一旦识别出换行分隔表格，其前后所有相关内容（包括表格的所有行列）都必须完整地放在同一个分段中，不得在表格中间任何位置创建分段边界\n" +
                "   c. 表格完整性保护：一旦识别到表格开始，必须执行以下强制保护措施：\n" +
                "      - 严禁在表格中间创建分段边界：表格的所有内容（包括表头、表体、表尾）必须放在同一个分段中\n" +
                "      - 特别针对空格分隔表格：对于'文字+空格+文字+空格'格式的表格（如PDF导出的表格内容），必须将连续的多行表格内容全部合并在同一个分段中，不能因为某些行的字符看起来像独立内容而分段\n" +
                "      - 空格分隔表格的完整性判断：当遇到连续多行都包含'文字+空格+文字'模式时，无论行数多少，都应视为同一个表格的全部内容，必须完整地放在一个分段中\n" +
                "      - 换行分隔表格的完整性判断：只有当连续5行以上且每行都是1-4个字符的短内容通过换行符分隔时，才视为同一个表格的列名或单元格内容，必须完整地放在一个分段中，不得因为单行看起来很短而分段。不满足此严格条件的内容应按正常段落处理。一旦识别出换行分隔表格，其前后所有相关内容（包括表格的所有行列和相邻的短行数据）都必须完整地放在同一个分段中，不得在表格中间任何位置创建分段边界\n" +
                "      - 表格前的说明文字：如果表格前有直接引用该表格的说明文字（如'如表X所示：'、'下表显示：'等），必须将说明文字和表格合并在同一个分段中\n" +
                "      - 表格后的说明文字：如果表格后有对该表格的补充说明或分析文字，也必须合并在同一个分段中\n" +
                "      - 即使表格内容很长（超过1000字），也必须完整地放在同一个分段中，不得为了控制分段长度而拆分表格\n" +
                "      - 只有当表格确实结束后（出现明显的段落分隔、章节标题、或其他明确的文本段落开始），才能开始新的分段\n" +
                "   d. 表格结束判断：表格结束的标志包括：\n" +
                "      - 出现明显的空行后接非表格文本\n" +
                "      - 出现新的章节标题（如'第X章'、'第X节'、'1.'、'2.'等）\n" +
                "      - 出现段落分隔符（连续的空行或明显的文本段落开始）\n" +
                "   e. 跨页表格处理：如果表格确实超过单个页面的合理长度限制（超过2000字），可以在逻辑子表格边界处谨慎拆分，但必须确保每个子表格都是完整和可理解的。\n" +
                "7. 每个分段的长度不应超过1000字（允许小幅浮动到800-1200字）。对于超过1000字的主章节，优先合并所有子级内容；只有确实超过1000字无法合并时，才在子级边界处拆分。\n" +
                "\n" +
                "输出要求：\n" +
                "1. 返回每个分段在原文中的起始位置（字符索引，从0开始）。\n" +
                "2. 返回每个分段起始位置的文本特征（前30-50个字符），用于位置验证。\n" +
                "3. 确保分段内容语义完整，不要遗漏任何内容。\n" +
                "\n" +
                "输出格式：\n" +
                "返回JSON对象，必须包含 'segments' 字段，值为对象数组。\n" +
                "每个对象包含两个字段：\n" +
                "- position: 起始位置（整数）\n" +
                "- startText: 起始文本特征（前30-50个字符）\n" +
                "\n" +
                "示例1（有章节标记的文档，章节标题与第一个小节必须合并）：\n" +
                "原文：'第一章 人工智能概述\\n人工智能是计算机科学的重要分支。\\n1.1 定义与范畴\\nAI的定义可以分为两大类。\\n1.2 发展历程\\n人工智能的发展经历了多个阶段。\\n第二章 机器学习\\n机器学习是AI的核心技术。'\n" +
                "应返回：{\n" +
                "  \"segments\": [\n" +
                "    {\"position\": 0, \"startText\": \"第一章 人工智能概述\"},\n" +
                "    {\"position\": 68, \"startText\": \"第二章 机器学习\"}\n" +
                "  ]\n" +
                "}\n" +
                "说明：第一章标题必须与1.1、1.2合并在一起，不能将\"第一章 人工智能概述\"单独分段。\n" +
                "\n" +
                "反例（以下分段方式是错误的，严禁这样分段）：\n" +
                "错误示例1：将\"第一章 人工智能概述\"单独作为一个分段，将1.1和1.2合并为另一个分段。这完全错误，章节标题必须与第一个小节合并。\n" +
                "错误示例2：将1.1和1.2分别作为独立分段。因为1.1和1.2是第一章的子节，且总字数不超过1000字，必须合并到第一章分段中。\n" +
                "同理：如果第八章下有8.1、8.2、8.3等小节，且各小节内容加起来不超过1000字，则必须将\"第八章\"、8.1、8.2、8.3全部合并在同一个分段中，即使8.2或8.3中包含表格也不能单独分段。\n" +
                "\n" +
                "反例2（严禁将\"第X节\"、\"第X条\"误认为新章节）：\n" +
                "错误做法：将\"第三章\"单独分段，将\"第一节\"单独分段，将\"第七条\"单独分段。这完全错误，因为\"第一节\"是第三章的子节，\"第七条\"是第三章第一节的子条，它们必须合并在第三章的分段中。\n" +
                "正确做法：\"第三章 成果等级划分\"后面的\"第一节\"、\"第七条\"、\"第八条\"、\"1. 2.\"、\"（1）（2）\"等内容都属于第三章的子级内容，必须合并在同一分段中（如果总字数不超过1000字则全部合为一段；如果超过1000字则在节或条的边界处拆分）。\n" +
                "\n" +
                "反例3（严禁跨章节合并，严禁章节标题单独成段）：\n" +
                "错误做法1：将第二章末尾的\"第六条\"和第三章的\"第三章 成果等级划分\"放在同一分段中——跨章节合并。\n" +
                "错误做法2：将\"第三章 成果等级划分\"单独作为一个分段（只有50字），将\"第一节\"作为独立分段——章节标题不能单独成段。\n" +
                "正确做法：\"第三章 成果等级划分\"必须和\"第一节\"、\"第七条\"合并在同一个分段中。如果第三章总内容超过1000字需要拆分，第一段从\"第三章\"开始到某个合适的小节边界（确保包含第一节），第二段从第二节开始继续后面的内容。\"第三章\"和\"第一节\"始终在同一个分段。\n" +
                "\n" +
                "示例1b（大章节拆分，超过1000字时）：\n" +
                "假设第十章内容超过1000字，包含10.1、10.2、10.3、10.4等小节，各小节内容如下：10.1(400字)、10.2(350字)、10.3(800字)、10.4(600字)。\n" +
                "则应拆分为3个分段：[第十章标题+10.1+10.2, 约750字]、[10.3, 约800字]、[10.4, 约600字]。在节边界处切分，保持每个小节完整。\n" +
                "如果大章节没有小节结构（如连续4000字的纯文本），则按语义段落或每约1000字切分，确保在句子结束处断开，保持每个分段的语义完整性。\n" +
                "\n" +
                "示例3（章节+小节+条的正确合并，章节标题必须与第一节合并）：\n" +
                "原文：'第三章 成果等级划分\\n根据成果认定范围与依据，将教学成果、科研成果、社会服务成果和其他成果总体上划分为A+级、A级、B级和C级。\\n\\n第一节 教学成果等级划分\\n第七条 A+级教学成果 1.A+级课程类成果：（1）国家级一流课程；（2）教育部课程思政示范课程；（3）教育部来华留学英语授课品牌课程；（4）其他国家级课程。\\n\\n第八条 A级教学成果 1.A级课程类成果：（1）省级一流课程；（2）省级课程思政示范课程...'\n" +
                "应返回：{\n" +
                "  \"segments\": [\n" +
                "    {\"position\": 0, \"startText\": \"第三章 成果等级划分\"}\n" +
                "  ]\n" +
                "}\n" +
                "说明：\"第三章\"标题必须与\"第一节\"合并在同一个分段中，不能将\"第三章 成果等级划分\"单独分段。虽然总字数超过1000字，但因为它们都是同一章节的层级结构（第三章→第一节→第七条/第八条），必须合并在同一分段中。这是【最高优先级】规则，必须严格遵守。\n" +
                "错误做法（严禁这样分段）：将\"第三章\"单独分段、将\"第一节\"单独分段、将\"第七条\"单独分段。这些做法完全错误，违反了章节标题必须与第一个小节合并的规则。\n" +
                "\n" +
                "示例2（无章节标记的纯文本，约1000字一段）：\n" +
                "原文：'小猫的故事 在我家院子里有一只小猫它叫橘子\\n记得橘子来到我们家那一天下着小雨妈妈在花坛边发现了它...'\n" +
                "应返回：{\n" +
                "  \"segments\": [\n" +
                "    {\"position\": 0, \"startText\": \"小猫的故事 在我家院子里有一只小猫\"},\n" +
                "    {\"position\": 150, \"startText\": \"记得橘子来到我们家那一天下着小雨\"}\n" +
                "  ]\n" +
                "}\n" +
                "\n" +
                "注意事项：\n" +
                "1. position必须尽量准确，指向分段的第一个字符。\n" +
                "2. startText用于验证position的准确性。\n" +
                "3. 返回的JSON格式必须正确，不要包含任何额外文本或markdown标记。\n" +
                "4. 确保每个分段都是完整的语义单元，避免内容断层。\n" +
                "5. 对于无标点的文本，根据语义自然断句来确定分段位置。\n" +
                "6. 短章节（1000字以内）的小节合并规则优先级最高，章节标题必须与第一个小节合并；大章节（超过1000字）的拆分规则同样具有高优先级，两种规则不冲突。";
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(String content) {
        return "请分析以下文章内容，按章节语义进行划分，返回每个分段的位置、起始文本特征和完整内容：\n" +
                "\n" +
                content +
                "\n" +
                "\n" +
                "请严格按照上述JSON格式返回分段信息，确保语义完整性。";
    }
}
