package com.xspaceagi.agent.core.infra.component.workflow.handler;

import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.NodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.agent.core.spec.utils.UrlFile;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;

@Slf4j
public class DocumentExtractNodeHandler extends AbstractNodeHandler {

    @Override
    public Object executeNode(WorkflowContext workflowContext, WorkflowNodeDto node) {
        NodeConfigDto nodeConfig = node.getNodeConfig();
        Map<String, Object> params = extraBindValueMap(workflowContext, node, nodeConfig.getInputArgs());
        if (params.isEmpty()) {
            return Map.of("output", "");
        }
        // Get the first parameter value
        String url = String.valueOf(params.values().toArray()[0]);
        if (StringUtils.isBlank(url) || !url.startsWith("http")) {
            return Map.of("output", "");
        }

        DataTypeEnum dataType = getDocumentTypeFromUrl(url, nodeConfig.getInputArgs().get(0));
        if (dataType == null) {
            return Map.of("output", "");
        }

        String output;
        try {
            switch (dataType) {
                case File_Doc:
                    // Process Word document
                    output = UrlFile.wordToMarkdown(url);
                    break;
                case File_Excel:
                    // Process Excel document
                    output = UrlFile.excelToJson(url);
                    break;
                case File_Txt:
                    // Process Txt document
                    output = UrlFile.urlToText(url, "UTF-8");
                    break;
                default:
                    output = UrlFile.parseToString(url);
                    break;
            }
        } catch (Exception e) {
            log.warn("Failed to parse document from URL: {}", url, e);
            // ignore
            try {
                output = UrlFile.parseToString(url);
            } catch (Exception ex) {
                log.warn("Failed to parse document from URL: {}", url, ex);
                output = "Failed to read document: " + e.getMessage();
            }
        }

        return Map.of("output", output);
    }

    private DataTypeEnum getDocumentTypeFromUrl(String url, Arg arg) {
        // Implement document type retrieval logic according to actual requirements here
        if (arg.getDataType() != null && arg.getDataType().name().startsWith("File")) {
            return arg.getDataType();
        }
        String url0 = url;
        url = url.toLowerCase();
        if (url.endsWith(".pdf")) {
            return DataTypeEnum.File_PDF;
        } else if (url.endsWith(".doc") || url.endsWith(".docx")) {
            return DataTypeEnum.File_Doc;
        } else if (url.endsWith(".xls") || url.endsWith(".xlsx")) {
            return DataTypeEnum.File_Excel;
        } else if (url.endsWith(".ppt") || url.endsWith(".pptx")) {
            return DataTypeEnum.File_PPT;
        } else if (url.endsWith(".txt")) {
            return DataTypeEnum.File_Txt;
        } else if (url.endsWith(".text")) {
            return DataTypeEnum.File_Txt;
        } else if (url.endsWith(".json")) {
            return DataTypeEnum.File_Txt;
        } else if (url.endsWith(".html")) {
            return DataTypeEnum.File_Txt;
        } else if (url.endsWith(".htm")) {
            return DataTypeEnum.File_Txt;
        } else if (url.endsWith(".md") || url.endsWith(".markdown") || url.endsWith(".mdown") || url.endsWith(".mkd")) {
            return DataTypeEnum.File_Txt;
        } else {
            try {
                URL fileUrl = new URL(url0);
                URLConnection connection = fileUrl.openConnection();
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                connection.connect();
                String cType = connection.getContentType();
                if (cType != null) {
                    if (cType.contains("pdf")) {
                        return DataTypeEnum.File_PDF;
                    } else if (cType.contains("word")) {
                        return DataTypeEnum.File_Doc;
                    } else if (cType.contains("excel")) {
                        return DataTypeEnum.File_Excel;
                    } else if (cType.contains("ppt")) {
                        return DataTypeEnum.File_PPT;
                    } else if (cType.contains("text")) {
                        return DataTypeEnum.File_Txt;
                    }
                }
            } catch (IOException e) {
                // ignore
            }
        }

        return null;
    }
}
