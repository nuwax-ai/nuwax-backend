package com.xspaceagi.knowledge.domain.docparser.impl;

import com.xspaceagi.agent.core.adapter.application.WorkflowApplicationService;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowExecutor;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.knowledge.core.spec.enums.KnowledgeDataTypeEnum;
import com.xspaceagi.knowledge.domain.docparser.FileParseRequest;
import com.xspaceagi.knowledge.domain.docparser.FileParseService;
import com.xspaceagi.knowledge.domain.docparser.parse.DocParser;
import com.xspaceagi.knowledge.domain.model.KnowledgeConfigModel;
import com.xspaceagi.knowledge.domain.model.KnowledgeDocumentModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeConfigRepository;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import com.xspaceagi.system.application.dto.UserDto;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TikaParser implements DocParser {

    @Resource
    private FileParseService fileParseService;

    @Resource
    private IKnowledgeConfigRepository knowledgeConfigRepository;

    @Resource
    private WorkflowApplicationService workflowApplicationService;

    @Resource
    private WorkflowExecutor workflowExecutor;

    @Override
    public void chunk(
            KnowledgeDocumentModel documentDto,
            UserContext userContext
    ) {
        var dataType = documentDto.getDataType();
        var dataTypeEnum = KnowledgeDataTypeEnum.getEnumByCode(dataType);
        if (Objects.isNull(dataTypeEnum)) {
            log.error("Failed to parse document, unsupported file type [{}]", dataType);
            throw KnowledgeException.build(
                    BizExceptionCodeEnum.knowledgeDocumentUnsupportedType
            );
        }

        var docUrl = documentDto.getDocUrl();

        try {
            // 检查是否为PDF文件且需要使用工作流解析
            if (
                    shouldUseWorkflowParsing(documentDto.getKbId())
            ) {
                log.info("Parse doc via workflow, docId={}, docUrl={}", documentDto.getId(), docUrl);
                String content = parseWithWorkflow(
                        documentDto.getKbId(),
                        docUrl,
                        userContext
                );

                // 构建 FileParseRequest 并调用 fileParseService 进行解析
                FileParseRequest fileParseRequest = FileParseRequest.builder()
                        .kbId(documentDto.getKbId())
                        .docId(documentDto.getId())
                        .spaceId(documentDto.getSpaceId())
                        .content(content)
                        .segmentConfig(documentDto.getSegmentConfig())
                        .build();

                fileParseService.parseRawTxt(fileParseRequest, userContext);
                return;
            }

            // 下载文件
            URL url = new URL(docUrl);
            URLConnection connection = url.openConnection();
            try (InputStream stream = connection.getInputStream()) {
                // 使用 Apache Tika 解析文件内容
                Parser parser = new AutoDetectParser();
                BodyContentHandler handler = new BodyContentHandler(-1);
                Metadata metadata = new Metadata();
                ParseContext context = new ParseContext();

                parser.parse(stream, handler, metadata, context);

                String content = handler.toString();

                // 构建 FileParseRequest 并调用 fileParseService 进行解析
                FileParseRequest fileParseRequest = FileParseRequest.builder()
                        .kbId(documentDto.getKbId())
                        .docId(documentDto.getId())
                        .spaceId(documentDto.getSpaceId())
                        .content(content)
                        .segmentConfig(documentDto.getSegmentConfig())
                        .build();
                fileParseService.parseRawTxt(fileParseRequest, userContext);

            }
        } catch (IOException | SAXException | TikaException e) {
            log.error("Failed to parse document,docUrl={}", docUrl, e);
            throw KnowledgeException.build(
                    BizExceptionCodeEnum.knowledgeDocumentParseFailed
            );
        }
    }

    @Override
    public Boolean isSupport(Integer dataType, String docUrl) {
        // 根据 dataType 判断是否支持
        var dataTypeEnum = KnowledgeDataTypeEnum.getEnumByCode(dataType);
        return KnowledgeDataTypeEnum.CUSTOM_TEXT != dataTypeEnum;
    }

    /**
     * 检查是否为PDF文件
     */
    private boolean isPdfFile(String docUrl) {
        if (StringUtils.isBlank(docUrl)) {
            return false;
        }

        try {
            // 1. 首先通过URL扩展名快速判断
            String lowerUrl = docUrl.toLowerCase();
            if (lowerUrl.endsWith(".pdf") || lowerUrl.contains(".pdf?")) {
                return true;
            }

            // 2. 通过HTTP响应头Content-Type判断（不下载文件内容）
            URL url = new URL(docUrl);
            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(5000); // 5秒超时
            connection.setReadTimeout(5000);

            // 重要：设置请求方法为HEAD，这样服务器就不会返回文件内容
            if (connection instanceof HttpURLConnection) {
                ((HttpURLConnection) connection).setRequestMethod("HEAD");
            }

            // 只获取响应头，不下载文件内容
            String contentType = connection.getContentType();
            if (
                    contentType != null && contentType.toLowerCase().contains("pdf")
            ) {
                return true;
            }

            // 检查Content-Disposition头部，可能包含文件名信息
            String contentDisposition = connection.getHeaderField(
                    "Content-Disposition"
            );
            if (
                    contentDisposition != null &&
                            contentDisposition.toLowerCase().contains(".pdf")
            ) {
                return true;
            }
        } catch (Exception e) {
            log.debug("PDF type detect failed, docUrl={}", docUrl, e);
            // 如果检测失败，回退到URL扩展名判断
            String lowerUrl = docUrl.toLowerCase();
            return lowerUrl.endsWith(".pdf") || lowerUrl.contains(".pdf?");
        }

        return false;
    }

    /**
     * 检查是否应该使用工作流解析
     */
    private boolean shouldUseWorkflowParsing(Long kbId) {
        if (kbId == null) {
            return false;
        }

        try {
            KnowledgeConfigModel knowledgeConfig =
                    knowledgeConfigRepository.queryOneInfoById(kbId);
            return (
                    knowledgeConfig != null &&
                            knowledgeConfig.getWorkflowId() != null
            );
        } catch (Exception e) {
            log.error("Knowledge config query failed, kbId={}", kbId, e);
            return false;
        }
    }

    /**
     * 使用工作流解析PDF文件
     */
    private String parseWithWorkflow(
            Long kbId,
            String docUrl,
            UserContext userContext
    ) {
        try {
            KnowledgeConfigModel knowledgeConfig =
                    knowledgeConfigRepository.queryOneInfoById(kbId);
            if (
                    knowledgeConfig == null ||
                            knowledgeConfig.getWorkflowId() == null
            ) {
                throw KnowledgeException.build(
                        BizExceptionCodeEnum.knowledgeDocumentParseFailed
                );
            }

            // 查询发布的工作流配置
            WorkflowConfigDto workflowConfigDto =
                    workflowApplicationService.queryPublishedWorkflowConfig(
                            knowledgeConfig.getWorkflowId(),
                            null, // 传空不校验权限
                            true
                    );

            if (workflowConfigDto == null) {
                throw KnowledgeException.build(
                        BizExceptionCodeEnum.knowledgeDocumentParseFailed
                );
            }

            // 创建用户对象
            UserDto userDto = new UserDto();
            userDto.setTenantId(userContext.getTenantId());
            userDto.setId(userContext.getUserId());
            userDto.setTenantId(userContext.getTenantId());
            userDto.setUserName(userContext.getUserName());
            userDto.setNickName(userContext.getNickName());
            userDto.setEmail(userContext.getEmail());
            userDto.setPhone(userContext.getPhone());
            userDto.setAvatar(userContext.getAvatar());
            RequestContext.get().setUser(userDto);

            // 构建工作流执行上下文
            String requestId = UUID.randomUUID().toString();
            AgentContext agentContext = new AgentContext();
            agentContext.setRequestId(requestId);
            agentContext.setConversationId(requestId);
            agentContext.setUser(userDto);
            agentContext.setUserId(userDto.getId());
            agentContext.setUserName(userDto.getNickName() != null ? userDto.getNickName() : userDto.getUserName());
            List<Arg> inputArgs = workflowConfigDto.getStartNode().getNodeConfig().getInputArgs();
            if (inputArgs == null || inputArgs.isEmpty()) {
                throw KnowledgeException.build(
                        BizExceptionCodeEnum.knowledgeDocumentParseFailed
                );
            }
            Map<String, Object> variableParams = new HashMap<>();
            inputArgs.forEach(arg -> {
                if (arg.getDataType() == DataTypeEnum.String || arg.getDataType().name().startsWith("File")) {
                    variableParams.put(arg.getName(), docUrl);
                }
                if (arg.getDataType() == DataTypeEnum.Array_String || arg.getDataType().name().startsWith("Array_File")) {
                    variableParams.put(arg.getName(), List.of(docUrl));
                }
            });
            WorkflowContext workflowContext = new WorkflowContext();
            workflowContext.setAgentContext(agentContext);
            workflowContext.setRequestId(requestId);
            workflowContext.setWorkflowConfig(workflowConfigDto);
            workflowContext.setParams(variableParams);

            // 执行工作流
            Object object = workflowExecutor.execute(workflowContext).block();

            // 获取执行结果
            String result = workflowContext.getEndNodeContent();
            if (StringUtils.isBlank(result)) {
                if (object instanceof Map && ((Map<?, ?>) object).size() > 0) {
                    //将map的值转换为字符串
                    result = ((Map<?, ?>) object).values().stream().map(Object::toString).collect(Collectors.joining("\n\n"));
                } else {
                    throw KnowledgeException.build(
                            BizExceptionCodeEnum.knowledgeDocumentParseFailed
                    );
                }
            }

            return result;
        } catch (Exception e) {
            log.error("Workflow PDF parse failed, kbId={}, docUrl={}", kbId, docUrl, e);
            throw KnowledgeException.build(
                    BizExceptionCodeEnum.knowledgeDocumentParseFailed
            );
        }
    }
}
