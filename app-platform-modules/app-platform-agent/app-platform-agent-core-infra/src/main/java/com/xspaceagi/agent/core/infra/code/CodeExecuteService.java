package com.xspaceagi.agent.core.infra.code;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.HttpClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
public class CodeExecuteService {

    @Resource
    private HttpClient httpClient;

    @Value("${code.execute.url:}")
    private String url;

    public CodeExecuteResultDto execute(CodeArgDto codeArgDto) {
        String body = JSON.toJSONString(codeArgDto);
        String res = null;
        try {
            res = httpClient.post(url, body, Map.of());
            JSONObject resJsonObject = JSON.parseObject(res);
            CodeExecuteResultDto codeExecuteResultDto = new CodeExecuteResultDto();
            //resJsonObject转CodeExecuteResultDto
            codeExecuteResultDto.setSuccess(resJsonObject.getBoolean("success"));
            codeExecuteResultDto.setError(resJsonObject.getString("error"));
            if (codeExecuteResultDto.getSuccess() != null && codeExecuteResultDto.getSuccess()) {
                JSONObject data = resJsonObject.getJSONObject("data");
                String result = data.getString("result");
                if (JSON.isValid(result)) {
                    codeExecuteResultDto.setResult(data.get("result"));
                } else {
                    codeExecuteResultDto.setResult(result);
                }
                if (data.getJSONArray("logs") != null) {
                    codeExecuteResultDto.setLogs(data.getJSONArray("logs").toList(String.class));
                }
            }
            codeExecuteResultDto.setError(codeExecuteResultDto.getError() != null ? codeExecuteResultDto.getError().replace("JS engine error:", "") : "");
            return codeExecuteResultDto;
        } catch (Exception e) {
            log.error("代码执行接口调用失败", e);
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentCodeExecuteUnavailable);
        } finally {
            log.info("代码执行接口调用结果，输入 {}, 输出 {}", body, res);
        }
    }
}
