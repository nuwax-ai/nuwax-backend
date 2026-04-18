package com.xspaceagi.agent.core.infra.component.plugin.handler;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.HttpPluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.HttpNodeConfigDto;
import com.xspaceagi.agent.core.infra.component.ArgExtractUtil;
import com.xspaceagi.agent.core.infra.component.plugin.PluginContext;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.agent.core.spec.enums.SystemArgNameEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.HttpClient;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.entity.ContentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.*;
import java.util.stream.Collectors;

import static com.xspaceagi.agent.core.infra.component.ArgExtractUtil.extraParams;

@Component
public class HttpPluginHandler extends AbstractPluginHandler {

    private static HttpClient httpClient;

    @Autowired
    public void setHttpClient(HttpClient httpClient) {
        HttpPluginHandler.httpClient = httpClient;
    }

    protected Object execute0(PluginContext pluginContext) {
        HttpPluginConfigDto httpPluginConfigDto = (HttpPluginConfigDto) pluginContext.getPluginConfig();
        Map<String, Object> header = new HashMap<>();
        Map<String, Object> query = new HashMap<>();
        Map<String, Object> body = new HashMap<>();
        Map<String, Object> path = new HashMap<>();
        String apiUrl = httpPluginConfigDto.getUrl();
        Assert.notNull(apiUrl, "url cannot be left blank.");
        // 提取参数
        for (Arg inputArg : httpPluginConfigDto.getInputArgs()) {
            Object value = extraParams(inputArg, pluginContext.getParams());
            if ((value == null || value.equals("")) && inputArg.isRequire()) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                        ArgExtractUtil.requireArgMsg(inputArg));
            }
            if (value == null) {
                continue;
            }
            switch (inputArg.getInputType()) {
                case Header:
                    header.put(inputArg.getName(), value);
                    break;
                case Query:
                    query.put(inputArg.getName(), value);
                    break;
                case Body:
                    body.put(inputArg.getName(), value);
                    break;
                case Path:
                    path.put(inputArg.getName(), value);
                    break;
            }
        }

        String result = null;
        // 发送请求
        switch (httpPluginConfigDto.getMethod()) {
            case GET:
                result = httpClient.get(apiUrl, query, header, path);
                break;
            case POST:
                ContentType contentType = httpPluginConfigDto.getContentType() == HttpNodeConfigDto.ContentTypeEnum.JSON ? ContentType.APPLICATION_JSON : ContentType.APPLICATION_FORM_URLENCODED;
                result = httpClient.post(apiUrl, path, query, header, body, contentType);
                break;
            case PUT:
                contentType = httpPluginConfigDto.getContentType() == HttpNodeConfigDto.ContentTypeEnum.JSON ? ContentType.APPLICATION_JSON : ContentType.APPLICATION_FORM_URLENCODED;
                result = httpClient.put(apiUrl, path, query, header, body, contentType);
                break;
            case DELETE:
                result = httpClient.delete(apiUrl, path, query, header);
        }

        HttpResponse httpResponse = httpClient.getLastResponse();
        Map<String, Object> outputMap = new LinkedHashMap<>();
        if (result != null && JSON.isValid(result)) {
            if (result.startsWith("[")) {
                result = "{\"ROOT_ARRAY\":" + result + "}";
            }
            Map<String, Object> resultMap = JSON.parseObject(result);
            //null时为解析参数时传递
            if (httpPluginConfigDto.getOutputArgs() == null) {
                outputMap.putAll(resultMap);
                httpPluginConfigDto.setOutputArgs(new ArrayList<>());
            }
            resetRequireToFalse(httpPluginConfigDto.getOutputArgs());
            for (Arg outputArg : httpPluginConfigDto.getOutputArgs()) {
                if (SystemArgNameEnum.HTTP_BODY.name().equals(outputArg.getName())) {
                    outputMap.put(SystemArgNameEnum.HTTP_BODY.name(), result);
                    continue;
                }
                if (SystemArgNameEnum.HTTP_STATUS_CODE.name().equals(outputArg.getName())) {
                    outputMap.put(SystemArgNameEnum.HTTP_STATUS_CODE.name(), httpResponse.getStatusLine().getStatusCode());
                    continue;
                }
                if ((outputArg.getDataType() == DataTypeEnum.Object || outputArg.getDataType() == DataTypeEnum.Array_Object) && CollectionUtils.isEmpty(outputArg.getSubArgs())) {
                    outputMap.put(outputArg.getName(), resultMap.get(outputArg.getName()));
                    continue;
                }
                Object value = extraParams(outputArg, resultMap);
                if (value != null) {
                    outputMap.put(outputArg.getName(), value);
                }
            }
        }
        if (httpResponse != null && pluginContext.isTest()) {
            //将所有headers组成json返回
            Map<String, String> headersMap = Arrays.stream(httpResponse.getAllHeaders())
                    .collect(Collectors.toMap(Header::getName, Header::getValue, (v1, v2) -> v1 + "," + v2));
            outputMap.put(SystemArgNameEnum.HTTP_HEADERS.name(), JSON.toJSONString(headersMap));
            outputMap.put(SystemArgNameEnum.HTTP_STATUS_CODE.name(), httpResponse.getStatusLine().getStatusCode());
            outputMap.put(SystemArgNameEnum.HTTP_BODY.name(), result);
        }
        return outputMap;
    }
}
