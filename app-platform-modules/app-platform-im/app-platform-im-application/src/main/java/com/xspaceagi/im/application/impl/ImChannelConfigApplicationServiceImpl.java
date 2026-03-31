package com.xspaceagi.im.application.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.im.application.ImChannelConfigApplicationService;
import com.xspaceagi.im.application.dto.ImChannelConfigDto;
import com.xspaceagi.im.application.dto.ImChannelStatisticsResponse;
import com.xspaceagi.im.application.wechat.WechatIlinkLongPollService;
import com.xspaceagi.im.domain.service.ImChannelConfigDomainService;
import com.xspaceagi.im.infra.dao.enitity.ImChannelConfig;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.im.infra.enums.ImOutputModeEnum;
import com.xspaceagi.im.infra.enums.ImTargetTypeEnum;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ImChannelConfigApplicationServiceImpl implements ImChannelConfigApplicationService {

    @Resource
    private ImChannelConfigDomainService imChannelConfigDomainService;
    @Lazy
    @Resource
    private WechatIlinkLongPollService wechatIlinkLongPollService;

    @Override
    public ImChannelConfigDto getFeishuConfigByAppId(String appId) {
        return getConfig(ImChannelEnum.FEISHU.getCode(), ImTargetTypeEnum.BOT, appId);
    }

    @Override
    public ImChannelConfigDto getDingtalkConfigByRobotCode(String robotCode) {
        return getConfig(ImChannelEnum.DINGTALK.getCode(), ImTargetTypeEnum.BOT, robotCode);
    }

    @Override
    public ImChannelConfigDto getWeworkBotConfigByToken(String token) {
        return getConfig(ImChannelEnum.WEWORK.getCode(), ImTargetTypeEnum.BOT, token);
    }

    @Override
    public ImChannelConfigDto getWeworkAppConfigByToken(String token) {
        return getConfig(ImChannelEnum.WEWORK.getCode(), ImTargetTypeEnum.APP, token);
    }

    @Override
    public List<ImChannelConfigDto> listWeworkBotConfigsByPage(int offset, int limit) {
        // 跨租户分页查询企业微信智能机器人配置（用于 webhook 回调场景）
        List<ImChannelConfig> configs = TenantFunctions.callWithIgnoreCheck(() -> {
            ImChannelConfig query = new ImChannelConfig();
            query.setChannel(ImChannelEnum.WEWORK.getCode());
            query.setTargetType(ImTargetTypeEnum.BOT.getCode());
            query.setEnabled(true);
            return imChannelConfigDomainService.listByPage(query, offset, limit);
        });
        if (configs == null || configs.isEmpty()) {
            return Collections.emptyList();
        }
        return configs.stream()
                .map(this::toDto)
                .filter(cfg -> cfg != null && cfg.getWeworkBot() != null)
                .collect(Collectors.toList());
    }

    @Override
    public List<ImChannelConfigDto> listWechatIlinkEnabledByPage(int offset, int limit) {
        List<ImChannelConfig> configs = TenantFunctions.callWithIgnoreCheck(() -> {
            ImChannelConfig query = new ImChannelConfig();
            query.setChannel(ImChannelEnum.WECHAT_ILINK.getCode());
            query.setTargetType(ImTargetTypeEnum.BOT.getCode());
            query.setEnabled(true);
            return imChannelConfigDomainService.listByPage(query, offset, limit);
        });
        if (configs == null || configs.isEmpty()) {
            return Collections.emptyList();
        }
        return configs.stream()
                .map(this::toDto)
                .filter(cfg -> cfg != null && cfg.getWechatIlink() != null)
                .collect(Collectors.toList());
    }

    @Override
    public ImChannelConfigDto getWechatIlinkConfigByIlinkAccountId(String ilinkAccountId) {
        return getConfig(ImChannelEnum.WECHAT_ILINK.getCode(), ImTargetTypeEnum.BOT, ilinkAccountId);
    }

    @Override
    public ImChannelConfig findWechatIlinkConfigEntityByIlinkUserId(Long tenantId, Long spaceId, String ilinkUserId) {
        if (tenantId == null || spaceId == null || StringUtils.isBlank(ilinkUserId)) {
            return null;
        }
        ImChannelConfig query = new ImChannelConfig();
        query.setChannel(ImChannelEnum.WECHAT_ILINK.getCode());
        query.setTargetType(ImTargetTypeEnum.BOT.getCode());
        query.setSpaceId(spaceId);
        query.setTenantId(tenantId);
        List<ImChannelConfig> all = imChannelConfigDomainService.list(query);
        if (CollectionUtils.isEmpty(all)) {
            return null;
        }
        return all.stream()
                .filter(c -> ilinkUserId.equals(extractIlinkUserIdFromConfigData(c.getConfigData())))
                .max(Comparator.comparing(ImChannelConfig::getId))
                .orElse(null);
    }

    @Override
    public List<ImChannelConfigDto> listEnabledWechatIlinkInSpace(Long spaceId, Long tenantId) {
        if (spaceId == null || tenantId == null) {
            return Collections.emptyList();
        }
        ImChannelConfig query = new ImChannelConfig();
        query.setChannel(ImChannelEnum.WECHAT_ILINK.getCode());
        query.setTargetType(ImTargetTypeEnum.BOT.getCode());
        query.setSpaceId(spaceId);
        query.setTenantId(tenantId);
        query.setEnabled(true);
        List<ImChannelConfig> raw = imChannelConfigDomainService.list(query);
        if (CollectionUtils.isEmpty(raw)) {
            return Collections.emptyList();
        }
        return raw.stream()
                .map(this::toDto)
                .filter(cfg -> cfg != null
                        && cfg.getWechatIlink() != null
                        && StringUtils.isNotBlank(cfg.getWechatIlink().getBotToken()))
                .collect(Collectors.toList());
    }

    @Override
    public Long resolveExplicitOrUniqueWechatIlinkConfigId(Long spaceId, Long tenantId, Long explicitConfigId) {
        List<ImChannelConfigDto> list = listEnabledWechatIlinkInSpace(spaceId, tenantId);
        if (explicitConfigId != null) {
            boolean ok = list.stream().anyMatch(d -> explicitConfigId.equals(d.getId()));
            if (!ok) {
                throw new BizException("指定的 im_channel_config 不存在、未启用或不是当前空间下可用的微信 iLink 渠道");
            }
            return explicitConfigId;
        }
        if (list.size() == 1) {
            return list.get(0).getId();
        }
        if (list.isEmpty()) {
            throw new BizException("当前空间未配置可用的微信 iLink 渠道");
        }
        throw new BizException("存在多条启用的微信 iLink 配置，主动/定时任务须显式指定 im_channel_config.id（对齐 openclaw-weixin 1.0.3 delivery.accountId）");
    }

    private static String extractIlinkUserIdFromConfigData(String configData) {
        if (StringUtils.isBlank(configData)) {
            return null;
        }
        try {
            JSONObject json = JSON.parseObject(configData);
            return json != null ? json.getString("ilinkUserId") : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<ImChannelConfigDto> listWeworkAppConfigsByPage(int offset, int limit) {
        // 跨租户分页查询企业微信自建应用配置（用于 webhook 回调场景）
        List<ImChannelConfig> configs = TenantFunctions.callWithIgnoreCheck(() -> {
            ImChannelConfig query = new ImChannelConfig();
            query.setChannel(ImChannelEnum.WEWORK.getCode());
            query.setTargetType(ImTargetTypeEnum.APP.getCode());
            query.setEnabled(true);
            return imChannelConfigDomainService.listByPage(query, offset, limit);
        });
        if (configs == null || configs.isEmpty()) {
            return Collections.emptyList();
        }
        return configs.stream()
                .map(this::toDto)
                .filter(cfg -> cfg != null && cfg.getWeworkApp() != null)
                .collect(Collectors.toList());
    }

    private ImChannelConfigDto getConfig(String channel, ImTargetTypeEnum targetType, String imTargetId) {
        if (StringUtils.isBlank(imTargetId)) {
            return null;
        }
        // 跨租户查询，需要忽略租户检查
        ImChannelConfig cfg = TenantFunctions.callWithIgnoreCheck(() ->
                imChannelConfigDomainService.findOne(channel, targetType.getCode(), imTargetId)
        );
        if (cfg == null || StringUtils.isBlank(cfg.getConfigData())) {
            return null;
        }
        return toDto(cfg);
    }

    private ImChannelConfigDto toDto(ImChannelConfig cfg) {
        JSONObject json = JSON.parseObject(cfg.getConfigData());
        if (json == null) {
            return null;
        }

        ImChannelConfigDto dto = new ImChannelConfigDto();
        dto.setId(cfg.getId());
        dto.setEnabled(cfg.getEnabled());
        dto.setTenantId(cfg.getTenantId());
        dto.setUserId(cfg.getUserId());
        dto.setAgentId(cfg.getAgentId());
        dto.setOutputMode(cfg.getOutputMode());
        dto.setChannel(cfg.getChannel());
        dto.setTargetType(cfg.getTargetType());

        String channel = cfg.getChannel();
        ImTargetTypeEnum targetType = ImTargetTypeEnum.fromCode(cfg.getTargetType());

        if (ImChannelEnum.FEISHU.getCode().equals(channel)) {
            ImChannelConfigDto.FeishuConfig feishu = new ImChannelConfigDto.FeishuConfig();
            feishu.setAppId(json.getString("appId"));
            feishu.setAppSecret(json.getString("appSecret"));
            feishu.setVerificationToken(json.getString("verificationToken"));
            feishu.setEncryptKey(json.getString("encryptKey"));
            dto.setFeishu(feishu);
        } else if (ImChannelEnum.DINGTALK.getCode().equals(channel)) {
            ImChannelConfigDto.DingtalkConfig ding = new ImChannelConfigDto.DingtalkConfig();
            ding.setClientId(json.getString("clientId"));
            ding.setClientSecret(json.getString("clientSecret"));
            ding.setRobotCode(json.getString("robotCode"));
            dto.setDingtalk(ding);
        } else if (ImChannelEnum.WEWORK.getCode().equals(channel)) {
            if (targetType == ImTargetTypeEnum.BOT) {
                ImChannelConfigDto.WeworkBotConfig bot = new ImChannelConfigDto.WeworkBotConfig();
                bot.setAibotId(json.getString("aibotId"));
                bot.setCorpId(json.getString("corpId"));
                bot.setCorpSecret(json.getString("corpSecret"));
                bot.setToken(json.getString("token"));
                bot.setEncodingAesKey(json.getString("encodingAesKey"));
                dto.setWeworkBot(bot);
            } else if (targetType == ImTargetTypeEnum.APP) {
                ImChannelConfigDto.WeworkAppConfig app = new ImChannelConfigDto.WeworkAppConfig();
                app.setAgentId(json.getString("agentId"));
                app.setCorpId(json.getString("corpId"));
                app.setCorpSecret(json.getString("corpSecret"));
                app.setToken(json.getString("token"));
                app.setEncodingAesKey(json.getString("encodingAesKey"));
                dto.setWeworkApp(app);
            }
        } else if (ImChannelEnum.WECHAT_ILINK.getCode().equals(channel) && targetType == ImTargetTypeEnum.BOT) {
            ImChannelConfigDto.WechatIlinkConfig w = new ImChannelConfigDto.WechatIlinkConfig();
            w.setBaseUrl(json.getString("baseUrl"));
            w.setBotToken(json.getString("botToken"));
            w.setBotType(json.getString("botType"));
            w.setCdnBaseUrl(json.getString("cdnBaseUrl"));
            w.setIlinkAccountId(json.getString("ilinkAccountId"));
            w.setIlinkUserId(json.getString("ilinkUserId"));
            dto.setWechatIlink(w);
        }

        return dto;
    }

    @Override
    public List<ImChannelConfig> list(ImChannelConfig query) {
        RequestContext<?> requestContext = RequestContext.get();
        if (requestContext == null || requestContext.getTenantId() == null) {
            throw new BizException("获取租户信息失败");
        }
        if (query.getSpaceId() == null) {
            throw new BizException("spaceId不能为空");
        }

        return imChannelConfigDomainService.list(query);
    }

    @Override
    public ImChannelConfig getById(Long id) {
        return imChannelConfigDomainService.getById(id);
    }

    @Override
    public ImChannelConfigDto getDtoById(Long id) {
        // 微信 iLink 长轮询等后台线程无 HTTP RequestContext，需忽略租户插件对 RequestContext 的校验
        return TenantFunctions.callWithIgnoreCheck(() -> {
            ImChannelConfig cfg = imChannelConfigDomainService.getById(id);
            if (cfg == null || StringUtils.isBlank(cfg.getConfigData())) {
                return null;
            }
            return toDto(cfg);
        });
    }

    @Override
    public ImChannelConfig add(ImChannelConfig config) {
        // 校验必要参数
        if (config == null) {
            throw new BizException("配置信息不能为空");
        }
        if (config.getSpaceId() == null) {
            throw new BizException("spaceId不能为空");
        }
        if (StringUtils.isBlank(config.getChannel())) {
            throw new BizException("渠道类型不能为空");
        }
        if (StringUtils.isBlank(config.getTargetType())) {
            throw new BizException("目标类型不能为空");
        }
        if (config.getAgentId() == null) {
            throw new BizException("关联智能体ID不能为空");
        }
        if (StringUtils.isBlank(config.getConfigData())) {
            throw new BizException("配置数据不能为空");
        }

        ImChannelEnum imChannelEnum = ImChannelEnum.fromCode(config.getChannel());
        ImTargetTypeEnum imTargetTypeEnum = ImTargetTypeEnum.fromCode(config.getTargetType());

        // 从 configData 中解析 targetId
        String targetId = extractTargetIdFromConfigData(config.getConfigData(), imChannelEnum, imTargetTypeEnum);
        if (StringUtils.isBlank(targetId)) {
            throw new BizException("无法从配置数据中解析目标唯一标识");
        }
        config.setTargetId(targetId);

        if (!ImOutputModeEnum.isValid(config.getOutputMode())) {
            config.setOutputMode(getDefaultOutputMode(config).getCode());
        }

        if (imChannelEnum == ImChannelEnum.WECHAT_ILINK && imTargetTypeEnum == ImTargetTypeEnum.BOT) {
            return addWechatIlinkBotConfig(config);
        }

        RequestContext<?> requestContext = RequestContext.get();
        if (requestContext == null || requestContext.getTenantId() == null) {
            throw new BizException("获取租户信息失败");
        }
        var userDto = (UserDto) RequestContext.get().getUser();
        config.setUserId(userDto.getId());
        config.setTenantId(requestContext.getTenantId());
        config.setCreatorId(userDto.getId());
        config.setCreatorName(userDto.getUserName());
        config.setYn(1);

        // 检查是否已存在相同配置
        ImChannelConfig existing = imChannelConfigDomainService.findOne(
                config.getChannel(),
                config.getTargetType(),
                config.getTargetId()
        );
        if (existing != null) {
            throw new BizException("该渠道配置已存在");
        }

        config = imChannelConfigDomainService.add(config);
        if (imChannelEnum == ImChannelEnum.WECHAT_ILINK) {
            notifyWechatIlinkPollWorkers(config.getId());
        }
        return config;
    }

    /**
     * 微信 iLink：同一 ilinkUserId 或同一 ilinkAccountId(targetId) 时走更新，否则新增（与扫码流程对齐）。
     */
    private ImChannelConfig addWechatIlinkBotConfig(ImChannelConfig config) {
        RequestContext<?> requestContext = RequestContext.get();
        if (requestContext == null || requestContext.getTenantId() == null) {
            throw new BizException("获取租户信息失败");
        }
        Long tenantId = requestContext.getTenantId();
        Long spaceId = config.getSpaceId();
        String ilinkUserId = extractIlinkUserIdFromConfigData(config.getConfigData());
        String normalized = config.getTargetId();

        ImChannelConfig existingByIlinkUser = null;
        if (StringUtils.isNotBlank(ilinkUserId) && spaceId != null) {
            existingByIlinkUser = findWechatIlinkConfigEntityByIlinkUserId(tenantId, spaceId, ilinkUserId);
        }
        if (existingByIlinkUser != null) {
            ImChannelConfig patch = new ImChannelConfig();
            patch.setId(existingByIlinkUser.getId());
            patch.setChannel(ImChannelEnum.WECHAT_ILINK.getCode());
            patch.setTargetType(ImTargetTypeEnum.BOT.getCode());
            patch.setConfigData(config.getConfigData());
            patch.setEnabled(config.getEnabled() != null ? config.getEnabled() : true);
            patch.setOutputMode(config.getOutputMode());
            patch.setName(StringUtils.isNotBlank(config.getName()) ? config.getName() : existingByIlinkUser.getName());
            patch.setAgentId(config.getAgentId());
            return update(patch, existingByIlinkUser);
        }

        ImChannelConfig existingByAccount = imChannelConfigDomainService.findOneIgnoreEnabled(
                ImChannelEnum.WECHAT_ILINK.getCode(), ImTargetTypeEnum.BOT.getCode(), normalized);
        if (existingByAccount != null) {
            ImChannelConfig patch = new ImChannelConfig();
            patch.setId(existingByAccount.getId());
            patch.setChannel(existingByAccount.getChannel());
            patch.setTargetType(existingByAccount.getTargetType());
            patch.setConfigData(config.getConfigData());
            patch.setEnabled(config.getEnabled() != null ? config.getEnabled() : true);
            patch.setOutputMode(config.getOutputMode());
            patch.setName(StringUtils.isNotBlank(config.getName()) ? config.getName() : existingByAccount.getName());
            patch.setAgentId(config.getAgentId());
            return update(patch, existingByAccount);
        }

        ImChannelConfig dup = imChannelConfigDomainService.findOne(
                config.getChannel(), config.getTargetType(), config.getTargetId());
        if (dup != null) {
            throw new BizException("该渠道配置已存在");
        }

        var userDto = (UserDto) RequestContext.get().getUser();
        config.setUserId(userDto.getId());
        config.setTenantId(tenantId);
        config.setCreatorId(userDto.getId());
        config.setCreatorName(userDto.getUserName());
        config.setYn(1);
        if (StringUtils.isBlank(config.getName())) {
            config.setName("微信iLink");
        }

        config = imChannelConfigDomainService.add(config);
        notifyWechatIlinkPollWorkers(config.getId());
        return config;
    }

    @Override
    public ImChannelConfig update(ImChannelConfig config, ImChannelConfig exist) {
        // 校验必要参数
        if (config == null) {
            throw new BizException("配置信息不能为空");
        }
        if (config.getId() == null) {
            throw new BizException("ID不能为空");
        }

        // 从 configData 中解析 targetId
        ImChannelEnum imChannelEnum = ImChannelEnum.fromCode(config.getChannel());
        ImTargetTypeEnum imTargetTypeEnum = ImTargetTypeEnum.fromCode(config.getTargetType());
        String targetId = extractTargetIdFromConfigData(config.getConfigData(), imChannelEnum, imTargetTypeEnum);
        if (StringUtils.isBlank(targetId)) {
            throw new BizException("无法从配置数据中解析目标唯一标识");
        }
        config.setTargetId(targetId);

        if (StringUtils.isNotBlank(config.getOutputMode()) && !ImOutputModeEnum.isValid(config.getOutputMode())) {
            config.setOutputMode(null);
        }

        // 设置修改者信息
        RequestContext<?> requestContext = RequestContext.get();
        if (requestContext != null) {
            config.setUserId(requestContext.getUserId());
            config.setModifiedId(requestContext.getUserId());
        }
        config.setTenantId(null); // 保持租户不变
        config.setSpaceId(null);// 保持空间不变

        // 如果修改了关键字段，检查是否与其他配置冲突
        if (!exist.getTargetId().equals(config.getTargetId())
                || !exist.getTargetType().equals(config.getTargetType())
                || !exist.getChannel().equals(config.getChannel())) {
            ImChannelConfig duplicate = imChannelConfigDomainService.findOne(
                    config.getChannel(),
                    config.getTargetType(),
                    config.getTargetId()
            );
            if (duplicate != null && !duplicate.getId().equals(config.getId())) {
                throw new BizException(String.format("该渠道配置已存在: %s %s %s",
                        ImChannelEnum.fromCode(config.getChannel()).getName(),
                        ImTargetTypeEnum.fromCode(config.getTargetType()).getName(), config.getTargetId()));
            }
        }

        config = imChannelConfigDomainService.updateById(config);
        if (imChannelEnum == ImChannelEnum.WECHAT_ILINK) {
            notifyWechatIlinkPollWorkers(config.getId());
        }
        return config;
    }

    @Override
    public boolean updateEnabled(ImChannelConfig config) {
        boolean ok = imChannelConfigDomainService.updateEnabled(config);
        if (ok) {
            ImChannelConfig full = imChannelConfigDomainService.getById(config.getId());
            if (full != null && ImChannelEnum.WECHAT_ILINK.getCode().equals(full.getChannel())) {
                notifyWechatIlinkPollWorkers(config.getId());
            }
        }
        return ok;
    }

    @Override
    public void disableWechatIlinkOnSessionExpired(Long configId) {
        if (configId == null) {
            return;
        }
        TenantFunctions.callWithIgnoreCheck(() -> {
            ImChannelConfig c = imChannelConfigDomainService.getById(configId);
            if (c == null || !ImChannelEnum.WECHAT_ILINK.getCode().equals(c.getChannel())) {
                return null;
            }
            if (Boolean.FALSE.equals(c.getEnabled())) {
                return null;
            }
            c.setEnabled(false);
            boolean ok = updateEnabled(c);
            if (ok) {
                log.info("wechat ilink session expired or invalid, disabled channel config, configId={}", configId);
            } else {
                log.warn("wechat ilink disable on session expired failed, configId={}", configId);
            }
            return null;
        });
    }

    private void notifyWechatIlinkPollWorkers(Long configId) {
        try {
            wechatIlinkLongPollService.forceReloadAndPollOnce(configId);
        } catch (Exception e) {
            log.warn("wechat ilink forceReloadAndPollOnce failed, configId={}", configId, e);
        }
    }

    @Override
    public boolean delete(Long id) {
        ImChannelConfig exist = imChannelConfigDomainService.getById(id);
        boolean ok = imChannelConfigDomainService.delete(id);
        if (ok && exist != null && ImChannelEnum.WECHAT_ILINK.getCode().equals(exist.getChannel())) {
            try {
                wechatIlinkLongPollService.removePollForDeletedConfig(id);
            } catch (Exception e) {
                log.warn("wechat ilink removePollForDeletedConfig failed, configId={}", id, e);
            }
        }
        return ok;
    }

    @Override
    public List<ImChannelStatisticsResponse> statistics(Long spaceId) {
        if (spaceId == null) {
            throw new BizException("spaceId不能为空");
        }

        ImChannelConfig query = new ImChannelConfig();
        query.setSpaceId(spaceId);
        List<ImChannelConfig> configs = list(query);

        Map<String, Long> countMap = CollectionUtils.isEmpty(configs) ? Collections.emptyMap() : configs.stream()
                .filter(cfg -> StringUtils.isNotBlank(cfg.getChannel()))
                .collect(Collectors.groupingBy(ImChannelConfig::getChannel, Collectors.counting()));

        // 结果必须包含所有 ImChannelEnum：数据库里不存在则 count=0
        return Arrays.stream(ImChannelEnum.values())
                .map(channelEnum -> {
                    Long count = countMap.getOrDefault(channelEnum.getCode(), 0L);
                    return ImChannelStatisticsResponse.builder()
                            .channel(channelEnum.getCode())
                            .channelName(channelEnum.getName())
                            .count(count)
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * 从配置数据中提取 targetId
     * 根据不同的渠道和目标类型，使用不同的字段作为 targetId：
     * - 飞书: appId
     * - 钉钉: robotCode
     * - 企业微信机器人: token（用于签名验证）
     * - 企业微信自建应用: token（用于签名验证）
     */
    private String extractTargetIdFromConfigData(String configData, ImChannelEnum channel, ImTargetTypeEnum targetType) {
        if (StringUtils.isBlank(configData)) {
            return null;
        }
        JSONObject json = JSON.parseObject(configData);
        if (json == null) {
            return null;
        }

        String targetId = null;

        if (channel == ImChannelEnum.FEISHU) {
            targetId = json.getString("appId");
        } else if (channel == ImChannelEnum.DINGTALK) {
            targetId = json.getString("robotCode");
        } else if (channel == ImChannelEnum.WEWORK) {
            // 企业微信使用 token 作为 targetId，因为签名是用 token 计算的
            // 可以通过签名验证找到对应的 token，然后直接查询配置
            targetId = json.getString("token");
        } else if (channel == ImChannelEnum.WECHAT_ILINK) {
            targetId = json.getString("ilinkAccountId");
        }

        return targetId;
    }

    private ImOutputModeEnum getDefaultOutputMode(ImChannelConfig config) {
        ImChannelEnum channel = ImChannelEnum.fromCode(config.getChannel());
        ImTargetTypeEnum targetType = ImTargetTypeEnum.fromCode(config.getTargetType());
        switch (channel) {
            case FEISHU:
                return ImOutputModeEnum.STREAM;
            case DINGTALK:
                return ImOutputModeEnum.STREAM;
            case WEWORK:
                if (targetType == ImTargetTypeEnum.BOT) {
                    return ImOutputModeEnum.ONCE;
                } else if (targetType == ImTargetTypeEnum.APP) {
                    return ImOutputModeEnum.ONCE;
                }
            case WECHAT_ILINK:
                return ImOutputModeEnum.ONCE;
            default:
                return ImOutputModeEnum.ONCE;
        }
    }

}
