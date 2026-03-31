package com.xspaceagi.im.application.wechat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.im.infra.dao.enitity.ImChannelConfig;
import com.xspaceagi.im.wechat.ilink.IlinkAccountIdNormalize;
import com.xspaceagi.im.wechat.ilink.IlinkConstants;
import com.xspaceagi.im.wechat.ilink.IlinkHttpClient;
import com.xspaceagi.im.wechat.ilink.dto.QrCodeStartResponse;
import com.xspaceagi.im.wechat.ilink.dto.QrCodeStatusResponse;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 微信 iLink 扫码：仅负责获取二维码与上游会话 {@link #pollStatus(String)}（确认后不落库）。
 * 落库请使用 {@link com.xspaceagi.im.application.ImChannelConfigApplicationService#add(ImChannelConfig)}（channel=wechat_ilink, targetType=bot），并传入扫码得到的 configData。
 */
@Slf4j
@Service
public class WechatIlinkQrService {

    private static final String SESSION_PREFIX = "wechat_ilink:qr_session:";
    private static final int SESSION_TTL_SECONDS = 900;

    @Resource
    private RedisUtil redisUtil;
    @Resource
    private IlinkHttpClient ilinkHttpClient;

    public QrStartResult startSession() {
        String sessionId = UUID.randomUUID().toString();
        String apiBase = IlinkConstants.DEFAULT_BASE_URL;
        String bt = IlinkConstants.DEFAULT_BOT_TYPE;
        try {
            QrCodeStartResponse qr = ilinkHttpClient.getBotQrCode(apiBase, bt);
            JSONObject o = new JSONObject();
            o.put("baseUrl", apiBase);
            o.put("botType", bt);
            o.put("qrcode", qr.getQrcode());
            o.put("qrcodeImgContent", qr.getQrcodeImgContent());
            redisUtil.set(SESSION_PREFIX + sessionId, o.toJSONString(), SESSION_TTL_SECONDS);
            return QrStartResult.builder()
                    .sessionId(sessionId)
                    .qrcode(qr.getQrcode())
                    .qrcodeImgContent(qr.getQrcodeImgContent())
                    .build();
        } catch (Exception e) {
            log.error("get_bot_qrcode failed", e);
            throw new BizException("获取微信二维码失败: " + e.getMessage());
        }
    }

    /**
     * 长轮询上游扫码状态（单次调用可能阻塞数十秒）。上游返回 confirmed 后<strong>不落库</strong>，仅将 token 等写入会话 Redis；
     * 前端将 configData 与 spaceId、agentId 一并提交 {@code POST /api/im-config/channel/add} 保存。
     */
    public QrPollResult pollStatus(String sessionId) {
        String raw = redisUtil.get(SESSION_PREFIX + sessionId) instanceof String s ? s : null;
        if (StringUtils.isBlank(raw)) {
            throw new BizException("扫码会话已过期或不存在");
        }
        JSONObject o = JSON.parseObject(raw);
        if ("confirmed".equals(o.getString("lastStatus"))) {
            return QrPollResult.builder()
                    .status("confirmed")
                    .configData(configDataJsonFromConfirmedSession(o))
                    .build();
        }
        String qrcode = o.getString("qrcode");
        String apiBase = o.getString("baseUrl");
        if (StringUtils.isBlank(qrcode)) {
            throw new BizException("会话数据异常");
        }
        try {
            QrCodeStatusResponse st = ilinkHttpClient.getQrCodeStatus(apiBase, qrcode,
                    (int) IlinkConstants.DEFAULT_LONG_POLL_TIMEOUT_MS);
            String stStr = st.getStatus();
            o.put("lastStatus", stStr);
            if ("confirmed".equals(stStr)) {
                o.put("botToken", st.getBotToken());
                o.put("loginBaseUrl", st.getBaseurl());
                o.put("ilinkBotId", st.getIlinkBotId());
                o.put("ilinkUserId", st.getIlinkUserId());
            }
            redisUtil.set(SESSION_PREFIX + sessionId, o.toJSONString(), SESSION_TTL_SECONDS);
            if ("confirmed".equals(stStr)) {
                return QrPollResult.builder()
                        .status("confirmed")
                        .configData(configDataJsonFromConfirmedSession(o))
                        .build();
            }
            return QrPollResult.builder()
                    .status(stStr)
                    .build();
        } catch (Exception e) {
            log.warn("get_qrcode_status: {}", e.getMessage());
            throw new BizException("查询扫码状态失败: " + e.getMessage());
        }
    }

    private String configDataJsonFromConfirmedSession(JSONObject o) {
        JSONObject cfgJson = buildWechatIlinkConfigDataFromSession(o);
        return cfgJson != null ? cfgJson.toJSONString() : null;
    }

    private JSONObject buildWechatIlinkConfigDataFromSession(JSONObject o) {
        if (o == null || !"confirmed".equals(o.getString("lastStatus"))) {
            return null;
        }
        String botToken = o.getString("botToken");
        String ilinkBotId = o.getString("ilinkBotId");
        if (StringUtils.isBlank(botToken) || StringUtils.isBlank(ilinkBotId)) {
            return null;
        }
        String normalized = IlinkAccountIdNormalize.normalizeForStorage(ilinkBotId);
        String loginBase = o.getString("loginBaseUrl");
        if (StringUtils.isBlank(loginBase)) {
            loginBase = o.getString("baseUrl");
        }
        JSONObject cfgJson = new JSONObject();
        cfgJson.put("baseUrl", loginBase);
        cfgJson.put("botToken", botToken);
        cfgJson.put("botType", o.getString("botType"));
        cfgJson.put("cdnBaseUrl", IlinkConstants.CDN_BASE_URL);
        cfgJson.put("ilinkAccountId", normalized);
        cfgJson.put("ilinkUserId", o.getString("ilinkUserId"));
        return cfgJson;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QrStartResult {
        private String sessionId;
        private String qrcode;
        private String qrcodeImgContent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QrPollResult {
        /**
         * 上游：wait / scaned / confirmed / expired。
         */
        private String status;
        /**
         * 与 {@link ImChannelConfig#getConfigData()} 同结构的 JSON 字符串；
         * {@code confirmed} 时来自会话预览。
         */
        private String configData;
    }
}
