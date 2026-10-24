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

    /**
     * 三方渠道回调里的「这次是不是群聊」判定。
     *
     * <p>★每个渠道的 {@code chatType} 取值口径各不相同（见各 case），没有统一约定，所以这里只能逐个写死。
     *
     * <p>★{@link ImChannelEnum#NUWAX_IM} <b>不走这个方法</b>：它的群/私聊真值在智能体上下文里
     * （{@code IS_GROUP_MESSAGE} / {@code imMode}，以及 {@code GROUP_CONV_ID} 这组键在不在），
     * 平台侧不靠一个 {@code chatType} 字符串去猜。这里刻意抛异常而不是返回一个 {@code false}：
     * 返回值只用来拼展示名后缀，猜错不会报错、只会悄悄给私聊消息挂上「群聊」字样 —— 静默的错答案
     * 比一次明确的失败难查得多。目前没有任何调用方传 NUWAX_IM，所以这条分支正常情况下不可达。
     */
    public static boolean isGroupChat(ImChannelEnum channel, String chatType) {
        if (channel == null || StringUtils.isBlank(chatType)) {
            return false;
        }
        return switch (channel) {
            case FEISHU -> !"p2p".equals(chatType);
            case DINGTALK -> "2".equals(chatType);
            case WEWORK -> ImChatTypeEnum.GROUP == ImChatTypeEnum.fromCode(chatType);
            case WECHAT_ILINK -> false;
            case NUWAX_IM -> throw new IllegalArgumentException(
                    "NUWAX_IM 渠道的群/私聊判定不走 chatType，请读智能体上下文里的 IS_GROUP_MESSAGE / imMode");
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
