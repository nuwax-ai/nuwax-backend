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

    /**
     * 分页查询已启用的微信 iLink 配置（跨租户，供长轮询 worker 使用）
     */
    List<ImChannelConfigDto> listWechatIlinkEnabledByPage(int offset, int limit);

    /**
     * 按 ilinkAccountId 查询微信 iLink 配置
     */
    ImChannelConfigDto getWechatIlinkConfigByIlinkAccountId(String ilinkAccountId);

    /**
     * 同租户、同空间下按 {@code configData.ilinkUserId} 查找一条微信 iLink 配置（重复扫码时用于原地更新 targetId/configData）。
     * 若存在多条历史重复，取 id 最大的一条。
     */
    ImChannelConfig findWechatIlinkConfigEntityByIlinkUserId(Long tenantId, Long spaceId, String ilinkUserId);

    /**
     * 某空间下已启用且含 botToken 的微信 iLink 配置（用于主动/定时出站选择渠道）。
     */
    List<ImChannelConfigDto> listEnabledWechatIlinkInSpace(Long spaceId, Long tenantId);

    /**
     * 主动/定时任务须显式传 {@code im_channel_config.id}；若未传则仅当空间内仅有一条启用配置时允许推断，否则抛出业务异常（对齐 1.0.3 delivery.accountId）。
     */
    Long resolveExplicitOrUniqueWechatIlinkConfigId(Long spaceId, Long tenantId, Long explicitConfigId);

    List<ImChannelConfig> list(ImChannelConfig query);

    ImChannelConfig getById(Long id);

    /**
     * 按主键加载并解析为 DTO（含 configData）
     */
    ImChannelConfigDto getDtoById(Long id);

    ImChannelConfig add(ImChannelConfig config);

    ImChannelConfig update(ImChannelConfig config, ImChannelConfig exist);

    boolean updateEnabled(ImChannelConfig config);

    /**
     * 微信 iLink getUpdates 判定会话/授权已失效（如 errcode=-14 且游标已清空仍无法恢复）时，
     * 将对应 {@code im_channel_config} 记录 {@code enabled} 置为 false，表示需重新扫码连接。
     */
    void disableWechatIlinkOnSessionExpired(Long configId);

    /**
     * 删除配置（逻辑删除）
     */
    boolean delete(Long id);

    /**
     * 统计配置
     */
    List<ImChannelStatisticsResponse> statistics(Long spaceId);
}
