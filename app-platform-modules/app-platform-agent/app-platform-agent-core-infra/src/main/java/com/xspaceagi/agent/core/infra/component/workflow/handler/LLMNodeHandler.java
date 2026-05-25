package com.xspaceagi.agent.core.infra.component.workflow.handler;

import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.agent.core.adapter.dto.KnowledgeSearchConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.LLMNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.model.ModelContext;
import com.xspaceagi.agent.core.infra.component.model.ModelInvoker;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentConfig;
import com.xspaceagi.agent.core.infra.component.model.dto.ModelCallConfigDto;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.spec.enums.ComponentTypeEnum;
import com.xspaceagi.agent.core.spec.enums.OutputTypeEnum;
import com.xspaceagi.agent.core.spec.utils.PlaceholderParser;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.mcp.sdk.dto.McpToolDto;
import com.xspaceagi.system.sdk.common.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class LLMNodeHandler extends AbstractNodeHandler {

    @Override
    public Mono<Object> execute(WorkflowContext workflowContext, WorkflowNodeDto node) {
        log.debug("execute LLMNodeHandler,node {}", node);
        LLMNodeConfigDto llmNodeConfigDto = (LLMNodeConfigDto) node.getNodeConfig();
        if (llmNodeConfigDto.getModelConfig() == null){
            return Mono.error(new RuntimeException("The workflow model node configuration is empty. Please check if the related models have been taken offline."));
        }
        Map<String, Object> valueMap = extraBindValueMap(workflowContext, node, llmNodeConfigDto.getInputArgs());
        String systemPrompt = PlaceholderParser.resoleAndReplacePlaceholder(valueMap, llmNodeConfigDto.getSystemPrompt());
        String userPrompt = PlaceholderParser.resoleAndReplacePlaceholder(valueMap, llmNodeConfigDto.getUserPrompt());
        ModelContext modelContext = new ModelContext();
        AgentContext agentContext = workflowContext.getCopiedAgentContext();
        // Extract image URLs from userPrompt
        agentContext.setAttachments(extractAttachments(userPrompt));
        modelContext.setAgentContext(agentContext);
        modelContext.setModelConfig(llmNodeConfigDto.getModelConfig());
        modelContext.setConversationId(workflowContext.getAgentContext().getConversationId());
        modelContext.setRequestId(workflowContext.getRequestId());
        modelContext.setNodeExecutingConsumer(workflowContext.getNodeExecutingConsumer());
        ModelCallConfigDto modelCallConfigDto = new ModelCallConfigDto();
        modelCallConfigDto.setSystemPrompt(systemPrompt);
        modelCallConfigDto.setUserPrompt(userPrompt);
        modelCallConfigDto.setChatRound(0);
        modelCallConfigDto.setStreamCall(true);
        modelCallConfigDto.setOutputType(llmNodeConfigDto.getOutputType());
        if (!CollectionUtils.isEmpty(llmNodeConfigDto.getOutputArgs())) {
            modelCallConfigDto.setOutputArgs(llmNodeConfigDto.getOutputArgs().stream().map((arg) -> {
                arg.setRequire(true);
                return arg;
            }).toList());
        }
        modelCallConfigDto.setMaxTokens(llmNodeConfigDto.getMaxTokens());
        modelCallConfigDto.setTemperature(llmNodeConfigDto.getTemperature());
        modelCallConfigDto.setTopP(llmNodeConfigDto.getTopP());
        modelContext.setModelCallConfig(modelCallConfigDto);
        List<ComponentConfig> componentConfigs = convertToComponentConfig(llmNodeConfigDto.getSkillComponentConfigs(), valueMap);
        modelCallConfigDto.setComponentConfigs(componentConfigs);
        modelContext.setTraceContext(workflowContext.getTraceContext().next(TraceContext.TraceTargetType.Model, modelContext.getModelConfig().getId().toString(),
                modelContext.getModelConfig().getModel(), modelContext.getModelConfig().getName(), null));
        ModelInvoker modelInvoker = workflowContext.getWorkflowContextServiceHolder().getModelInvoker();
        return modelInvoker.invoke(modelContext).last().map(result -> {
            if (llmNodeConfigDto.getOutputType() == OutputTypeEnum.Text || llmNodeConfigDto.getOutputType() == OutputTypeEnum.Markdown) {
                if (!CollectionUtils.isEmpty(llmNodeConfigDto.getOutputArgs())) {
                    Map<String, Object> outputMap = new HashMap<>();
                    outputMap.put(llmNodeConfigDto.getOutputArgs().get(0).getName(), modelContext.getModelCallResult().getResponseText());
                    return outputMap;
                } else {
                    return modelContext.getModelCallResult().getResponseText();
                }
            } else {
                return modelContext.getModelCallResult().getData();
            }
        });
    }

    private List<ComponentConfig> convertToComponentConfig(List<LLMNodeConfigDto.SkillComponentConfigDto> skillComponentConfigs, Map<String, Object> params) {
        if (skillComponentConfigs == null) {
            return List.of();
        }
        List<ComponentConfig> componentConfigs = new ArrayList<>();
        for (LLMNodeConfigDto.SkillComponentConfigDto skillComponentConfig : skillComponentConfigs) {
            ComponentConfig componentConfig = new ComponentConfig();
            componentConfig.setName(skillComponentConfig.getName());
            componentConfig.setIcon(skillComponentConfig.getIcon());
            componentConfig.setTargetId(skillComponentConfig.getTypeId());
            componentConfig.setDescription(skillComponentConfig.getDescription());
            componentConfig.setParams(params);
            List<Arg> inputArgBindConfigs = skillComponentConfig.getInputArgBindConfigs();
            if (!CollectionUtils.isEmpty(inputArgBindConfigs)) {
                componentConfig.setInputArgs(inputArgBindConfigs);
            }

            switch (skillComponentConfig.getType()) {
                case Knowledge:
                    componentConfig.setType(ComponentTypeEnum.Knowledge);
                    KnowledgeSearchConfigDto knowledgeSearchConfigDto = new KnowledgeSearchConfigDto();
                    knowledgeSearchConfigDto.setSearchStrategy(skillComponentConfig.getKbRecallStrategy());
                    knowledgeSearchConfigDto.setMatchingDegree(skillComponentConfig.getKbMinMatchScore());
                    knowledgeSearchConfigDto.setMaxRecallCount(skillComponentConfig.getKbMaxRecallCount());
                    componentConfig.setTargetConfig(knowledgeSearchConfigDto);
                    break;
                case Plugin:
                    componentConfig.setType(ComponentTypeEnum.Plugin);
                    PluginDto pluginDto = (PluginDto) skillComponentConfig.getTargetConfig();
                    componentConfig.setTargetConfig(pluginDto);
                    componentConfig.setName(pluginDto.getName());
                    componentConfig.setFunctionName(pluginDto.getFunctionName());
                    componentConfig.setDescription(pluginDto.getDescription());
                    break;
                case Workflow:
                    componentConfig.setType(ComponentTypeEnum.Workflow);
                    WorkflowConfigDto workflowConfigDto = (WorkflowConfigDto) skillComponentConfig.getTargetConfig();
                    componentConfig.setTargetConfig(workflowConfigDto);
                    componentConfig.setName(workflowConfigDto.getName());
                    componentConfig.setFunctionName(workflowConfigDto.getFunctionName());
                    componentConfig.setDescription(workflowConfigDto.getDescription());
                    componentConfig.setOriginalTargetId(workflowConfigDto.getId());
                    break;
                case Mcp:
                    componentConfig.setType(ComponentTypeEnum.Mcp);
                    McpDto mcpDto = (McpDto) skillComponentConfig.getTargetConfig();
                    componentConfig.setTargetConfig(mcpDto);
                    componentConfig.setName(mcpDto.getName());
                    componentConfig.setFunctionName(skillComponentConfig.getToolName());
                    String toolDescription = getMcpToolDescription(mcpDto, skillComponentConfig.getToolName());
                    componentConfig.setDescription(toolDescription);
                    break;
                default:
                    break;
            }
            componentConfigs.add(componentConfig);
        }
        return componentConfigs;
    }

    private static String getMcpToolDescription(McpDto targetConfig, String toolName) {
        if (targetConfig.getDeployedConfig().getTools() == null) {
            return targetConfig.getDescription();
        }
        for (McpToolDto tool : targetConfig.getDeployedConfig().getTools()) {
            if (tool.getName().equals(toolName)) {
                return tool.getDescription();
            }
        }
        return targetConfig.getDescription();
    }

    private static final Map<String, String> MIME_TYPE_MAP = new HashMap<>();

    static {
        // Image formats
        MIME_TYPE_MAP.put("jpg", "image/jpeg");
        MIME_TYPE_MAP.put("jpeg", "image/jpeg");
        MIME_TYPE_MAP.put("png", "image/png");
        MIME_TYPE_MAP.put("gif", "image/gif");
        MIME_TYPE_MAP.put("bmp", "image/bmp");
        MIME_TYPE_MAP.put("webp", "image/webp");

        // Video formats
        MIME_TYPE_MAP.put("mp4", "video/mp4");
        MIME_TYPE_MAP.put("avi", "video/x-msvideo");
        MIME_TYPE_MAP.put("mov", "video/quicktime");
        MIME_TYPE_MAP.put("wmv", "video/x-ms-wmv");
        MIME_TYPE_MAP.put("flv", "video/x-flv");
        MIME_TYPE_MAP.put("mkv", "video/x-matroska");
        MIME_TYPE_MAP.put("webm", "video/webm");
        MIME_TYPE_MAP.put("m4v", "video/x-m4v");
        MIME_TYPE_MAP.put("3gp", "video/3gpp");

        // Audio formats
        MIME_TYPE_MAP.put("mp3", "audio/mpeg");
        MIME_TYPE_MAP.put("wav", "audio/wav");
        MIME_TYPE_MAP.put("aac", "audio/aac");
        MIME_TYPE_MAP.put("ogg", "audio/ogg");
        MIME_TYPE_MAP.put("flac", "audio/flac");
        MIME_TYPE_MAP.put("wma", "audio/x-ms-wma");
        MIME_TYPE_MAP.put("m4a", "audio/mp4");
    }

    private static List<AttachmentDto> extractAttachments(String text) {
        List<AttachmentDto> attachmentDtos = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return attachmentDtos;
        }

        try {
            // Regular expression to match URLs of image, video, and audio formats
            // 注意：这里只使用一个捕获组来捕获完整URL，扩展名通过字符串处理获取
            String regex = "https?://[^\\s\"']+?\\.(jpg|jpeg|png|gif|bmp|webp|mp4|avi|mov|wmv|flv|mkv|webm|m4v|3gp|mp3|wav|aac|ogg|flac|wma|m4a)(\\?\\S+)?";
            Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(text);

            while (matcher.find()) {
                String fileUrl = matcher.group(0); // 完整匹配到的URL

                // 从URL中提取扩展名（更可靠的方式）
                String extension = extractExtensionFromUrl(fileUrl);

                AttachmentDto attachmentDto = new AttachmentDto();
                attachmentDto.setFileUrl(fileUrl);
                attachmentDto.setMimeType(MIME_TYPE_MAP.getOrDefault(extension, "application/octet-stream"));
                attachmentDtos.add(attachmentDto);
            }
        } catch (Exception e) {
            log.warn("Error extracting attachment URLs: {}", e.getMessage());
        }
        return attachmentDtos;
    }

    /**
     * 从URL中提取文件扩展名（小写）
     */
    private static String extractExtensionFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }

        // 去掉查询参数
        String urlWithoutQuery = url.split("\\?")[0];

        // 从最后一个点号后面获取扩展名
        int lastDotIndex = urlWithoutQuery.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == urlWithoutQuery.length() - 1) {
            return "";
        }

        return urlWithoutQuery.substring(lastDotIndex + 1).toLowerCase();
    }
}
