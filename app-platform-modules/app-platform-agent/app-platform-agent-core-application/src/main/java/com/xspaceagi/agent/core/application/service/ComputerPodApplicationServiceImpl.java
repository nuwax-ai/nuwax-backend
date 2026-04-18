package com.xspaceagi.agent.core.application.service;

import com.xspaceagi.agent.core.adapter.application.ComputerPodApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ComputerPodResultDto;
import com.xspaceagi.agent.core.domain.service.ComputerPodDomainService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ComputerPodApplicationServiceImpl implements ComputerPodApplicationService {

    @Resource
    private ComputerPodDomainService computerPodDomainService;

    @Override
    public ComputerPodResultDto ensurePod(Long cId) {
        Long userId = getCurrentUserId();
        log.info("[ComputerPodApplication] ensurePod, cId={}, userId={}", cId, userId);
        return computerPodDomainService.ensurePod(cId, userId);
    }

    @Override
    public ComputerPodResultDto keepalive(Long cId) {
        Long userId = getCurrentUserId();
        log.info("[ComputerPodApplication] keepalive, cId={}, userId={}", cId, userId);
        return computerPodDomainService.keepalive(cId, userId);
    }

    @Override
    public ComputerPodResultDto restart(Long cId) {
        Long userId = getCurrentUserId();
        log.info("[ComputerPodApplication] restart, cId={}, userId={}", cId, userId);
        return computerPodDomainService.restart(cId, userId);
    }

    @Override
    public ComputerPodResultDto getVncStatus(Long cId) {
        Long userId = getCurrentUserId();
        log.info("[ComputerPodApplication] getVncStatus, cId={}, userId={}", cId, userId);
        return computerPodDomainService.getVncStatus(cId, userId);
    }

    private Long getCurrentUserId() {
        if (RequestContext.get() == null || RequestContext.get().getUserId() == null) {
            throw BizException.of(ErrorCodeEnum.UNAUTHORIZED, BizExceptionCodeEnum.userNotLoggedIn);
        }
        return RequestContext.get().getUserId();
    }
}


