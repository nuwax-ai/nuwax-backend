package com.xspaceagi.im.application.impl;

import com.xspaceagi.im.application.ImChannelConfigApplicationService;
import com.xspaceagi.im.application.WechatIlinkPushApplicationService;
import com.xspaceagi.im.application.dto.ImChannelConfigDto;
import com.xspaceagi.im.infra.dao.enitity.ImChannelConfig;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.im.wechat.ilink.IlinkConstants;
import com.xspaceagi.im.wechat.ilink.IlinkHttpClient;
import com.xspaceagi.im.wechat.ilink.WechatIlinkMessageHelper;
import com.xspaceagi.system.spec.exception.BizException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class WechatIlinkPushApplicationServiceImpl implements WechatIlinkPushApplicationService {

    @Resource
    private ImChannelConfigApplicationService imChannelConfigApplicationService;
    @Resource
    private IlinkHttpClient ilinkHttpClient;

    @Override
    public void pushText(Long imChannelConfigId, String text) {
        if (imChannelConfigId == null) {
            throw new BizException("imChannelConfigId 不能为空");
        }
        if (StringUtils.isBlank(text)) {
            throw new BizException("text 不能为空");
        }

        ImChannelConfig config = imChannelConfigApplicationService.getById(imChannelConfigId);
        if (config == null) {
            throw new BizException("配置不存在");
        }
        if (!ImChannelEnum.WECHAT_ILINK.getCode().equals(config.getChannel())) {
            throw new BizException("仅支持微信 iLink 渠道配置");
        }
        if (Boolean.FALSE.equals(config.getEnabled())) {
            throw new BizException("当前微信 iLink 配置未启用");
        }

        ImChannelConfigDto dto = imChannelConfigApplicationService.getDtoById(config.getId());
        ImChannelConfigDto.WechatIlinkConfig wechatIlink = dto != null ? dto.getWechatIlink() : null;
        if (wechatIlink == null) {
            throw new BizException("微信 iLink 配置缺失");
        }
        if (StringUtils.isBlank(wechatIlink.getBotToken())) {
            throw new BizException("微信 iLink 配置缺少 botToken");
        }
        if (StringUtils.isBlank(wechatIlink.getIlinkUserId())) {
            throw new BizException("微信 iLink 配置缺少 ilinkUserId");
        }

        String baseUrl = StringUtils.defaultIfBlank(wechatIlink.getBaseUrl(), IlinkConstants.DEFAULT_BASE_URL);
        try {
            ilinkHttpClient.sendMessage(
                    baseUrl,
                    wechatIlink.getBotToken(),
                    WechatIlinkMessageHelper.buildTextReply(wechatIlink.getIlinkUserId(), null, text),
                    15_000
            );
        } catch (Exception e) {
            log.error("wechat ilink push text failed, configId={}, ilinkUserId={}",
                    config.getId(), wechatIlink.getIlinkUserId(), e);
            throw new BizException("推送失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
    }
}
