package com.xspaceagi.agent.core.domain.service.impl;

import com.xspaceagi.agent.core.adapter.dto.ComputerPodResultDto;
import com.xspaceagi.agent.core.domain.service.ComputerPodDomainService;
import com.xspaceagi.agent.core.infra.rpc.ComputerPodClient;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class ComputerPodDomainServiceImpl implements ComputerPodDomainService {

    @Resource
    private ComputerPodClient computerPodClient;

    @Override
    public ComputerPodResultDto ensurePod(Long cId, Long userId) {
        checkParams(cId, userId, "ensurePod");
        Map<String, Object> result = computerPodClient.ensurePod(cId, userId);
        return parseResult(result, "ensurePod", cId, userId);
    }

    @Override
    public ComputerPodResultDto keepalive(Long cId, Long userId) {
        checkParams(cId, userId, "keepalive");
        Map<String, Object> result = computerPodClient.keepalive(cId, userId);
        return parseResult(result, "keepalive", cId, userId);
    }

    @Override
    public ComputerPodResultDto restart(Long cId, Long userId) {
        checkParams(cId, userId, "restart");
        Map<String, Object> result = computerPodClient.restart(cId, userId);
        return parseResult(result, "restart", cId, userId);
    }

    @Override
    public ComputerPodResultDto getVncStatus(Long cId, Long userId) {
        checkParams(cId, userId, "getVncStatus");
        Map<String, Object> result = computerPodClient.getVncStatus(cId, userId);
        return parseResult(result, "getVncStatus", cId, userId);
    }

    private void checkParams(Long cId, Long userId, String action) {
        if (cId == null || userId == null) {
            log.error("[ComputerPodDomainService] {} 参数为空, cId={}, userId={}", action, cId, userId);
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentRequiredParamEmpty);
        }
    }

    @SuppressWarnings("unchecked")
    private ComputerPodResultDto parseResult(Map<String, Object> result, String action, Long cId, Long userId) {
        if (result == null) {
            log.error("[ComputerPodDomainService] {} 结果为空, cId={}, userId={}", action, cId, userId);
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentContainerServiceCallFailed);
        }

        ComputerPodResultDto dto = new ComputerPodResultDto();
        
        // 解析 code
        Object codeObj = result.get("code");
        String code = codeObj == null ? null : String.valueOf(codeObj);
        dto.setCode(code);

        // 解析 message
        Object messageObj = result.get("message");
        String message = messageObj == null ? null : String.valueOf(messageObj);
        dto.setMessage(message);

        // 解析 data
        Object dataObj = result.get("data");
        if (dataObj instanceof Map) {
            dto.setData((Map<String, Object>) dataObj);
        }

        // 解析 tid
        Object tidObj = result.get("tid");
        if (tidObj != null) {
            dto.setTid(String.valueOf(tidObj));
        }

        log.info("[ComputerPodDomainService] {} 解析结果, cId={}, userId={}, code={}, message={}", 
                action, cId, userId, code, message);
        
        return dto;
    }
}


