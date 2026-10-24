package com.xspaceagi.im.application.support;

import com.xspaceagi.im.application.ImChannelConfigApplicationService;
import com.xspaceagi.im.application.dto.ImChannelConfigDto;
import com.xspaceagi.im.domain.service.ImChannelConfigDomainService;
import com.xspaceagi.im.infra.dao.enitity.ImChannelConfig;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.im.infra.enums.ImTargetTypeEnum;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 解析主动推送/绑定所用的 im_channel_config（按平台用户隔离；同用户多条启用时取最近修改的一条）。
 */
@Component
public class ImNotifyChannelConfigResolver {

    @Resource
    private ImChannelConfigApplicationService imChannelConfigApplicationService;
    @Resource
    private ImChannelConfigDomainService imChannelConfigDomainService;

    /**
     * @param userId 配置所属平台用户（绑定取当前用户；推送取目标用户）
     */
    public ImChannelConfigDto resolve(String channel, Long channelConfigId, Long userId) {
        if (userId == null || userId <= 0) {
            throw BizException.of(BizExceptionCodeEnum.imTenantOrUserMissing);
        }

        if (channelConfigId != null) {
            ImChannelConfigDto dto = imChannelConfigApplicationService.getDtoById(channelConfigId);
            Long tenantId = RequestContext.get() != null ? RequestContext.get().getTenantId() : null;
            if (dto == null || Boolean.FALSE.equals(dto.getEnabled()) || !channel.equals(dto.getChannel())
                    || !userId.equals(dto.getUserId())
                    || tenantId == null || !tenantId.equals(dto.getTenantId())) {
                throw BizException.of(BizExceptionCodeEnum.imNotifyChannelNotConfigured);
            }
            return dto;
        }

        Long tenantId = RequestContext.get() != null ? RequestContext.get().getTenantId() : null;
        if (tenantId == null) {
            throw BizException.of(BizExceptionCodeEnum.imTenantOrUserMissing);
        }

        ImChannelConfig query = new ImChannelConfig();
        query.setChannel(channel);
        query.setEnabled(true);
        query.setTenantId(tenantId);
        query.setUserId(userId);
        if (ImChannelEnum.WEWORK.getCode().equals(channel)) {
            query.setTargetType(ImTargetTypeEnum.APP.getCode());
        }
        // domain list 已按 modified 降序
        List<ImChannelConfig> list = imChannelConfigDomainService.list(query);
        if (list == null || list.isEmpty()) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyChannelNotConfigured);
        }
        return list.stream()
                .map(cfg -> imChannelConfigApplicationService.getDtoById(cfg.getId()))
                .filter(Objects::nonNull)
                .filter(dto -> Boolean.TRUE.equals(dto.getEnabled()))
                .filter(dto -> userId.equals(dto.getUserId()))
                .findFirst()
                .orElseThrow(() -> BizException.of(BizExceptionCodeEnum.imNotifyChannelNotConfigured));
    }
}
