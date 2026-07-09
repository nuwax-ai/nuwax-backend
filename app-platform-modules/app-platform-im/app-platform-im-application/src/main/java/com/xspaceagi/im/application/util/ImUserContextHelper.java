package com.xspaceagi.im.application.util;

import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.im.infra.enums.ImChatTypeEnum;
import com.xspaceagi.system.application.dto.UserDto;
import org.apache.commons.lang3.StringUtils;

/**
 * IM 渠道调用 Agent 前，重写 RequestContext 中的用户信息
 */
public final class ImUserContextHelper {

    private ImUserContextHelper() {
    }

    /**
     * 在原有 userName / nickName 后拼接 IM 标识
     */
    public static void rewriteUserForIm(UserDto userDto, ImChannelEnum channel, boolean groupChat,
                                        String sessionName, String imUserName) {
        if (userDto == null || channel == null) {
            return;
        }
        String suffix = buildSuffix(channel, groupChat, sessionName, imUserName);
        if (StringUtils.isNotBlank(userDto.getUserName())) {
            userDto.setUserName(userDto.getUserName() + suffix);
        }
        if (StringUtils.isNotBlank(userDto.getNickName())) {
            userDto.setNickName(userDto.getNickName() + suffix);
        }
    }

    public static boolean isGroupChat(ImChannelEnum channel, String chatType) {
        if (channel == null || StringUtils.isBlank(chatType)) {
            return false;
        }
        return switch (channel) {
            case FEISHU -> !"p2p".equals(chatType);
            case DINGTALK -> "2".equals(chatType);
            case WEWORK -> ImChatTypeEnum.GROUP == ImChatTypeEnum.fromCode(chatType);
            case WECHAT_ILINK -> false;
        };
    }

    private static String buildSuffix(ImChannelEnum channel, boolean groupChat,
                                      String sessionName, String imUserName) {
        String platform = channel.getName();
        if (groupChat) {
            String groupName = sessionName;
            String senderName = imUserName;
            boolean hasGroup = StringUtils.isNotBlank(groupName);
            boolean hasUser = StringUtils.isNotBlank(senderName);
            if (hasGroup && hasUser) {
                return "[" + platform + ":" + groupName + ":" + senderName + "]";
            }
            if (hasGroup) {
                return "[" + platform + ":" + groupName + "]";
            }
            return "[" + platform + "群聊]";
        }
        String userName = StringUtils.isNotBlank(imUserName) ? imUserName : sessionName;
        if (StringUtils.isNotBlank(userName)) {
            return "[" + platform + ":" + userName + "]";
        }
        return "[" + platform + "用户]";
    }
}
