package com.xspaceagi.agent.core.infra.component.workflow.handler;

import com.xspaceagi.agent.core.adapter.dto.config.workflow.TextProcessingNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.spec.utils.PlaceholderParser;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.text.StringEscapeUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class TextProcessNodeHandler extends AbstractNodeHandler {

    @Override
    public Mono<Object> execute(WorkflowContext workflowContext, WorkflowNodeDto node) {
        TextProcessingNodeConfigDto textProcessingNodeConfigDto = (TextProcessingNodeConfigDto) node.getNodeConfig();
        Map<String, Object> params = extraBindValueMap(workflowContext, node, textProcessingNodeConfigDto.getInputArgs());
        if (params.isEmpty()) {
            if (textProcessingNodeConfigDto.getTextHandleType() != TextProcessingNodeConfigDto.TextHandleTypeEnum.SPLIT) {
                String text = textProcessingNodeConfigDto.getText() == null ? "" : textProcessingNodeConfigDto.getText();
                return Mono.just(Map.of("output", PlaceholderParser.resoleAndReplacePlaceholder(params, text)));
            }
            return Mono.just(Map.of("output", List.of()));
        }
        if (textProcessingNodeConfigDto.getTextHandleType() == TextProcessingNodeConfigDto.TextHandleTypeEnum.SPLIT) {
            if (CollectionUtils.isEmpty(textProcessingNodeConfigDto.getSplits())) {
                return Mono.just(Map.of("output", List.of()));
            }
            String firstDelimiter = safeUnescape(textProcessingNodeConfigDto.getSplits().get(0));
            if (textProcessingNodeConfigDto.getSplits().get(0).equals("&nbsp;") || textProcessingNodeConfigDto.getSplits().get(0).equals(" ")) {
                firstDelimiter = " ";
            }
            List<String> textList = new ArrayList<>();
            // Get the first element of params
            for (Object object : params.values().toArray()) {
                String text = String.valueOf(object);
                // textProcessingNodeConfigDto.getSplits() splits text, getSplits is an array, many delimiters
                for (String split : textProcessingNodeConfigDto.getSplits()) {
                    String delimiter = safeUnescape(split);
                    if (split.equals("&nbsp;") || split.equals(" ")) {
                        delimiter = " ";
                        text = text.replaceAll("\\s+", " ");
                    }
                    text = text.replace(delimiter, firstDelimiter);
                }
                String[] texts = text.split(firstDelimiter);
                // Convert texts to List
                textList.addAll(Arrays.asList(texts));
            }
            return Mono.just(Map.of("output", textList));
        } else {
            String val = PlaceholderParser.resoleAndReplacePlaceholder(params, textProcessingNodeConfigDto.getText(), textProcessingNodeConfigDto.getJoin() == null ? "" : textProcessingNodeConfigDto.getJoin());
            return Mono.just(Map.of("output", val == null ? "" : val));
        }
    }

    private String safeUnescape(String str) {
        try {
            return StringEscapeUtils.unescapeJava(str);
        } catch (Exception e) {
            return str;
        }
    }
}
