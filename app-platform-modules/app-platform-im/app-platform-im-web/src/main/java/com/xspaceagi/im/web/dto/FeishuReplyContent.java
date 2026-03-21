package com.xspaceagi.im.web.dto;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.application.IComputerFileApplicationService;
import com.xspaceagi.im.web.service.ImFileShareService;
import com.xspaceagi.im.web.util.ImOutputProcessor;
import lombok.Builder;
import lombok.Data;


/**
 * 飞书回复消息内容。
 * 支持消息类型：text（文本）、post（富文本）、image（图片）、interactive（互动卡片）
 */
@Data
@Builder
public class FeishuReplyContent {

    /**
     * 消息类型：text、post、image、interactive
     */
    private String msgType;

    /**
     * 消息内容 JSON 字符串，格式依 msg_type 不同而不同
     */
    private String contentJson;

    /**
     * 创建纯文本回复
     */
    public static FeishuReplyContent text(String text) {
        if (text == null) {
            text = "";
        }
        String content = JSON.toJSONString(JSONObject.of("text", text));
        return FeishuReplyContent.builder()
                .msgType("text")
                .contentJson(content)
                .build();
    }

    /**
     * 创建富文本回复
     */
    public static FeishuReplyContent post(String title, String body) {
        if (body == null) {
            body = "";
        }
        if (title == null) {
            title = "";
        }
        Object[] paragraph = new Object[]{JSONObject.of("tag", "text", "text", body)};
        Object[] content = new Object[]{paragraph};
        JSONObject zhCn = new JSONObject();
        zhCn.put("title", title);
        zhCn.put("content", content);
        JSONObject post = new JSONObject();
        post.put("zh_cn", zhCn);
        String contentJson = JSON.toJSONString(JSONObject.of("post", post));
        return FeishuReplyContent.builder()
                .msgType("post")
                .contentJson(contentJson)
                .build();
    }

    /**
     * 创建 Markdown 富文本回复（非互动卡片）。
     * <p>
     * 注意：这里使用 post 的 md 组件，避免 interactive 卡片。
     */
    public static FeishuReplyContent postMarkdown(String title, String markdown) {
        if (markdown == null) {
            markdown = "";
        }
        if (title == null) {
            title = "";
        }
        Object[] paragraph = new Object[]{JSONObject.of("tag", "md", "text", markdown)};
        Object[] content = new Object[]{paragraph};
        JSONObject zhCn = new JSONObject();
        zhCn.put("title", title);
        zhCn.put("content", content);
        JSONObject post = new JSONObject();
        post.put("zh_cn", zhCn);
        String contentJson = JSON.toJSONString(JSONObject.of("post", post));
        return FeishuReplyContent.builder()
                .msgType("post")
                .contentJson(contentJson)
                .build();
    }

    /** 卡片 header 主题色 */
    private static final String CARD_HEADER_TEMPLATE = "indigo";

    /**
     * 创建简单文本卡片回复，支持流式 patch 更新。
     * 当 conversationId 不为空时，将 &lt;file&gt;filename&lt;/file&gt; 替换为文件 URL，
     * 若有 file 标签则追加：点击此链接查看会话：域名/home/chat/{conversationId}/{agentId}
     */
    public static FeishuReplyContent card(String text, boolean updateMulti, Long conversationId) {
        return card(text, updateMulti, conversationId, null);
    }

    /**
     * 创建简单文本卡片回复，支持流式 patch 更新。
     * 当 conversationId 不为空时，将 &lt;file&gt;filename&lt;/file&gt; 替换为：源文件名：链接，
     * 若有 file 标签且 agentId 不为空，则追加：点击此链接查看会话：域名/home/chat/{conversationId}/{agentId}
     */
    public static FeishuReplyContent card(String text, boolean updateMulti, Long conversationId, Long agentId) {
        return card(text, updateMulti, conversationId, agentId, null);
    }

    /**
     * 创建简单文本卡片回复，支持流式 patch 更新。
     * fileUrlDomain 为文件 URL 域名，为空时不进行 file 标签替换
     */
    public static FeishuReplyContent card(String text, boolean updateMulti, Long conversationId, Long agentId, String fileUrlDomain) {
        return card(text, updateMulti, conversationId, agentId, fileUrlDomain, null, null, null, null);
    }

    /**
     * 创建简单文本卡片回复，支持流式 patch 更新。
     * fileUrlDomain 为文件 URL 域名，为空时不进行 file 标签替换
     * 支持传入 userId、tenantId 和 fileShareService 以使用分享链接
     */
    public static FeishuReplyContent card(String text, boolean updateMulti, Long conversationId, Long agentId, String fileUrlDomain,
                                          Long userId, Long tenantId, ImFileShareService fileShareService,
                                          IComputerFileApplicationService computerFileApplicationService) {
        // 使用统一的输出处理工具
        text = ImOutputProcessor.processOutput(text, conversationId, agentId, fileUrlDomain, userId, tenantId, fileShareService, computerFileApplicationService);

        Object[] elements = new Object[]{buildMarkdownElement(text)};
        JSONObject config = new JSONObject();
        config.put("wide_screen_mode", true);
        if (updateMulti) {
            config.put("update_multi", true);
        }
        JSONObject card = new JSONObject();
        card.put("config", config);
        JSONObject header = new JSONObject();
        header.put("template", CARD_HEADER_TEMPLATE);
        header.put("title", JSONObject.of("tag", "plain_text", "content", ""));
        card.put("header", header);
        card.put("elements", elements);
        return FeishuReplyContent.builder()
                .msgType("interactive")
                .contentJson(JSON.toJSONString(card))
                .build();
    }

    /**
     * 构建 markdown 组件。使用 tag=markdown 以支持完整 Markdown（含表格等），lark_md 仅支持部分语法。
     */
    private static JSONObject buildMarkdownElement(String content) {
        JSONObject el = new JSONObject();
        el.put("tag", "markdown");
        el.put("content", content);
        return el;
    }

}
