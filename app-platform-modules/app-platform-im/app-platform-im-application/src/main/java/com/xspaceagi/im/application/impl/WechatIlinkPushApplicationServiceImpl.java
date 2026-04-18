package com.xspaceagi.im.application.impl;

import com.xspaceagi.im.application.ImChannelConfigApplicationService;
import com.xspaceagi.im.application.WechatIlinkPushApplicationService;
import com.xspaceagi.im.application.dto.ImChannelConfigDto;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.im.wechat.ilink.IlinkConstants;
import com.xspaceagi.im.wechat.ilink.IlinkHttpClient;
import com.xspaceagi.im.wechat.ilink.WechatIlinkMessageHelper;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
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
    public void pushText(Long configId, String message) {
        if (configId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "configId");
        }
        if (StringUtils.isBlank(message)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "message");
        }

        ImChannelConfigDto dto = imChannelConfigApplicationService.getDtoById(configId);
        pushText(dto, message);
    }

    @Override
    public void pushText(ImChannelConfigDto dto, String message) {
        if (StringUtils.isBlank(message)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "message");
        }
        if (dto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.configNotFound);
        }
        if (!ImChannelEnum.WECHAT_ILINK.getCode().equals(dto.getChannel())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.imWechatIlinkChannelOnly);
        }
        if (Boolean.FALSE.equals(dto.getEnabled())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.imWechatIlinkConfigDisabled);
        }

        ImChannelConfigDto.WechatIlinkConfig wechatIlink = dto.getWechatIlink();
        if (wechatIlink == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.imWechatIlinkConfigMissing);
        }
        if (StringUtils.isBlank(wechatIlink.getBotToken())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.imWechatIlinkBotTokenMissing);
        }
        if (StringUtils.isBlank(wechatIlink.getIlinkUserId())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.imWechatIlinkUserIdMissing);
        }

        String baseUrl = StringUtils.defaultIfBlank(wechatIlink.getBaseUrl(), IlinkConstants.DEFAULT_BASE_URL);
        try {
            ilinkHttpClient.sendMessage(
                    baseUrl,
                    wechatIlink.getBotToken(),
                    WechatIlinkMessageHelper.buildTextReply(wechatIlink.getIlinkUserId(), null, message),
                    15_000
            );
        } catch (Exception e) {
            log.error("wechat ilink push text failed, configId={}, ilinkUserId={}",
                    dto.getId(), wechatIlink.getIlinkUserId(), e);
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.imWechatIlinkPushFailed,
                    e.getMessage() != null ? e.getMessage() : "未知错误");
        }
    }
}
