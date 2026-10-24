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
    public ComputerPodResultDto ensurePod(Long cId, String appStage) {
        Long userId = getCurrentUserId();
        log.info("[ComputerPodApplication] ensurePod, cId={}, userId={}, appStage={}", cId, userId, appStage);
        return computerPodDomainService.ensurePod(cId, userId, appStage);
    }

    @Override
    public ComputerPodResultDto keepalive(Long cId, String appStage) {
        Long userId = getCurrentUserId();
        log.info("[ComputerPodApplication] keepalive, cId={}, userId={}, appStage={}", cId, userId, appStage);
        return computerPodDomainService.keepalive(cId, userId, appStage);
    }

    @Override
    public ComputerPodResultDto restart(Long cId, String appStage) {
        Long userId = getCurrentUserId();
        log.info("[ComputerPodApplication] restart, cId={}, userId={}, appStage={}", cId, userId, appStage);
        return computerPodDomainService.restart(cId, userId, appStage);
    }

    @Override
    public ComputerPodResultDto getVncStatus(Long cId, String appStage) {
        Long userId = getCurrentUserId();
        log.info("[ComputerPodApplication] getVncStatus, cId={}, userId={}, appStage={}", cId, userId, appStage);
        return computerPodDomainService.getVncStatus(cId, userId, appStage);
    }

    private Long getCurrentUserId() {
        if (RequestContext.get() == null || RequestContext.get().getUserId() == null) {
            throw BizException.of(ErrorCodeEnum.UNAUTHORIZED, BizExceptionCodeEnum.userNotLoggedIn);
        }
        return RequestContext.get().getUserId();
    }
}


