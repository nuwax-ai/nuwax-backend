package com.xspaceagi.agent.core.infra.component.workflow.handler;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.HttpNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.spec.enums.SystemArgNameEnum;
import com.xspaceagi.system.spec.utils.HttpClient;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.entity.ContentType;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static com.xspaceagi.agent.core.infra.component.ArgExtractUtil.extraParams;

public class HttpNodeHandler extends AbstractNodeHandler {

    public Object executeNode(WorkflowContext workflowContext, WorkflowNodeDto node) {
        HttpNodeConfigDto httpNodeConfigDto = (HttpNodeConfigDto) node.getNodeConfig();
        Map<String, Object> headers = extraBindValueMap(workflowContext, node, httpNodeConfigDto.getHeaders());
        Map<String, Object> queries = extraBindValueMap(workflowContext, node, httpNodeConfigDto.getQueries());
        Map<String, Object> body = extraBindValueMap(workflowContext, node, httpNodeConfigDto.getBody());
        String apiUrl = httpNodeConfigDto.getUrl();
        String result = null;
        HttpClient httpClient = workflowContext.getWorkflowContextServiceHolder().getHttpClient();
        ContentType contentType;
        // Send request
        switch (httpNodeConfigDto.getMethod()) {
            case GET:
                result = httpClient.get(apiUrl, queries, headers, Map.of());
                break;
            case POST:
                contentType = httpNodeConfigDto.getContentType() == HttpNodeConfigDto.ContentTypeEnum.JSON ? ContentType.APPLICATION_JSON : ContentType.APPLICATION_FORM_URLENCODED;
                result = httpClient.post(apiUrl, Map.of(), queries, headers, body, contentType);
                break;
            case PUT:
                contentType = httpNodeConfigDto.getContentType() == HttpNodeConfigDto.ContentTypeEnum.JSON ? ContentType.APPLICATION_JSON : ContentType.APPLICATION_FORM_URLENCODED;
                result = httpClient.put(apiUrl, Map.of(), queries, headers, body, contentType);
                break;
            case DELETE:
                result = httpClient.delete(apiUrl, Map.of(), queries, headers);
        }
        Map<String, Object> outputMap = new HashMap<>();
        HttpResponse httpResponse = httpClient.getLastResponse();
        if (httpResponse != null) {
            outputMap.put(SystemArgNameEnum.HTTP_STATUS_CODE.name(), httpResponse.getStatusLine().getStatusCode());
            // Convert all headers to JSON and return
            Map<String, String> headersMap = Arrays.stream(httpResponse.getAllHeaders())
                    .collect(Collectors.toMap(Header::getName, Header::getValue, (v1, v2) -> v1 + "," + v2));
            outputMap.put(SystemArgNameEnum.HTTP_HEADERS.name(), JSON.toJSONString(headersMap));
        }
        String sysBodyName = SystemArgNameEnum.HTTP_BODY.name();
        outputMap.put(sysBodyName, result);
        if (JSON.isValid(result)) {
            Map<String, Object> resultMap = JSON.parseObject(result);
            for (Arg outputArg : httpNodeConfigDto.getOutputArgs()) {
                if (sysBodyName.equals(outputArg.getName())) {
                    continue;
                }
                Object value = extraParams(outputArg, resultMap);
                if (value != null) {
                    outputMap.put(outputArg.getName(), value);
                }
            }
        }
        return outputMap;
    }
}
