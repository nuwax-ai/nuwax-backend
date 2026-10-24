package com.xspaceagi.im.application.dto;

import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import org.apache.commons.lang3.StringUtils;

/**
 * 渠道归一化：对外/对内统一为 wework / feishu / dingtalk。
 */
public final class ImNotifyChannel {

    private ImNotifyChannel() {
    }

    public static String normalize(String channel) {
        if (StringUtils.isBlank(channel)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "channel");
        }
        String c = channel.trim().toLowerCase();
        if (ImChannelEnum.WEWORK.getCode().equals(c)
                || ImChannelEnum.FEISHU.getCode().equals(c)
                || ImChannelEnum.DINGTALK.getCode().equals(c)) {
            return c;
        }
        throw BizException.of(BizExceptionCodeEnum.imNotifyChannelUnsupported, channel);
    }

    /** 对外展示渠道码（与内部一致）。 */
    public static String toExternal(String channel) {
        return channel;
    }
}
