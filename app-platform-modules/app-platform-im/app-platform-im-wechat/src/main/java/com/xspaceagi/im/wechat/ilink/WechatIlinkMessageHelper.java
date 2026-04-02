package com.xspaceagi.im.wechat.ilink;

import com.xspaceagi.im.wechat.ilink.dto.MessageItem;
import com.xspaceagi.im.wechat.ilink.dto.TextItem;
import com.xspaceagi.im.wechat.ilink.dto.WeixinMessage;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 消息类型常量（对齐 types.ts MessageItemType / MessageType）
 */
public final class WechatIlinkMessageHelper {

    public static final int ITEM_TEXT = 1;
    public static final int ITEM_IMAGE = 2;
    public static final int ITEM_VOICE = 3;
    public static final int ITEM_FILE = 4;
    public static final int ITEM_VIDEO = 5;

    public static final int MSG_USER = 1;
    public static final int MSG_BOT = 2;

    /** 与 types.ts MessageState.FINISH 一致；出站文本需为完成态，否则网关可能不落会话 */
    public static final int MSG_STATE_FINISH = 2;

    private WechatIlinkMessageHelper() {
    }

    /**
     * @param contextToken 可为 null；对齐 openclaw-weixin 1.0.3 时网关可能仍接受发送（以实际上游为准）。
     */
    public static WeixinMessage buildTextReply(String toUserId, String contextToken, String text) {
        TextItem ti = new TextItem();
        ti.setText(text);
        MessageItem item = new MessageItem();
        item.setType(ITEM_TEXT);
        item.setTextItem(ti);
        List<MessageItem> list = new ArrayList<>();
        list.add(item);
        WeixinMessage m = new WeixinMessage();
        // 对齐 openclaw-weixin buildTextMessageReq：BOT + FINISH + client_id + from_user_id 空串
        m.setFromUserId("");
        m.setClientId(UUID.randomUUID().toString());
        m.setMessageType(MSG_BOT);
        m.setMessageState(MSG_STATE_FINISH);
        m.setToUserId(toUserId);
        m.setContextToken(contextToken);
        m.setItemList(list);
        return m;
    }

    /**
     * 从入站消息提取首段文本（用于指令检测等）
     */
    public static String extractFirstText(WeixinMessage msg) {
        if (msg == null || msg.getItemList() == null) {
            return "";
        }
        for (MessageItem it : msg.getItemList()) {
            if (it == null || it.getType() == null) {
                continue;
            }
            if (it.getType() == ITEM_TEXT
                    && it.getTextItem() != null && StringUtils.isNotBlank(it.getTextItem().getText())) {
                return it.getTextItem().getText();
            }
            // 对齐 openclaw-weixin：语音消息若已携带转写文本，则优先作为正文输入。
            if (it.getType() == ITEM_VOICE
                    && it.getVoiceItem() != null && StringUtils.isNotBlank(it.getVoiceItem().getText())) {
                return it.getVoiceItem().getText();
            }
        }
        return "";
    }

    public static boolean isUserMessage(WeixinMessage msg) {
        return msg != null && msg.getMessageType() != null && msg.getMessageType() == MSG_USER;
    }
}
