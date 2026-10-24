package com.xspaceagi.agent.core.domain.service;

import com.xspaceagi.agent.core.adapter.dto.ComputerPodResultDto;

/**
 * 容器领域服务
 */
public interface ComputerPodDomainService {

    /**
     * 启动容器
     *
     * @param appStage dev/prod，userapp 容器环境，默认 dev
     */
    ComputerPodResultDto ensurePod(Long cId, Long userId, String appStage);

    /**
     * 容器保活
     *
     * @param appStage dev/prod，userapp 容器环境，默认 dev
     */
    ComputerPodResultDto keepalive(Long cId, Long userId, String appStage);

    /**
     * 重启容器（销毁后重建）
     *
     * @param appStage dev/prod，userapp 容器环境，默认 dev
     */
    ComputerPodResultDto restart(Long cId, Long userId, String appStage);

    /**
     * 查询容器 VNC 服务状态
     *
     * @param appStage dev/prod，userapp 容器环境，默认 dev
     */
    ComputerPodResultDto getVncStatus(Long cId, Long userId, String appStage);
}


