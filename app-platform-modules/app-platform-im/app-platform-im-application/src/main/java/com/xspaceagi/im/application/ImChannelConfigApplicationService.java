package com.xspaceagi.im.application;

import com.xspaceagi.im.application.dto.ImChannelConfigDto;
import com.xspaceagi.im.application.dto.ImChannelStatisticsResponse;
import com.xspaceagi.im.infra.dao.enitity.ImChannelConfig;

import java.util.List;

public interface ImChannelConfigApplicationService {

    ImChannelConfigDto getFeishuConfigByAppId(String appId);

    ImChannelConfigDto getDingtalkConfigByRobotCode(String robotCode);

    ImChannelConfigDto getWeworkBotConfigByToken(String token);

    ImChannelConfigDto getWeworkAppConfigByToken(String token);

    /**
     * 分页查询企业微信智能机器人配置
     */
    List<ImChannelConfigDto> listWeworkBotConfigsByPage(int offset, int limit);

    /**
     * 分页查询企业微信自建应用配置
     */
    List<ImChannelConfigDto> listWeworkAppConfigsByPage(int offset, int limit);

    List<ImChannelConfig> list(ImChannelConfig query);

    ImChannelConfig getById(Long id);

    ImChannelConfig add(ImChannelConfig config);

    ImChannelConfig update(ImChannelConfig config, ImChannelConfig exist);

    boolean updateEnabled(ImChannelConfig config);

    /**
     * 删除配置（逻辑删除）
     */
    boolean delete(Long id);

    /**
     * 统计配置
     */
    List<ImChannelStatisticsResponse> statistics(Long spaceId);
}
