package com.xspaceagi.im.application.wechat;

import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 持久化「渠道 + 微信用户」对应最近收到的 {@code context_token}（对齐 openclaw-weixin 1.0.3 落盘语义），
 * 进程重启后仍可用于出站/错误回复兜底。
 */
@Component
public class WechatIlinkContextTokenStore {

    private static final String CTX_HASH_PREFIX = "wechat_ilink:ctx:";

    @Resource
    private RedisUtil redisUtil;

    @Value("${wechat.ilink.context-token-ttl-seconds:1209600}")
    private long contextTokenTtlSeconds;

    private static String hashKey(Long configId) {
        return CTX_HASH_PREFIX + configId;
    }

    /**
     * 入站消息带非空 token 时写入；覆盖同用户上一条。
     */
    public void save(Long configId, String fromUserId, String contextToken) {
        if (configId == null || StringUtils.isBlank(fromUserId) || StringUtils.isBlank(contextToken)) {
            return;
        }
        String key = hashKey(configId);
        redisUtil.hashPut(key, fromUserId, contextToken);
        if (contextTokenTtlSeconds > 0) {
            redisUtil.expire(key, contextTokenTtlSeconds);
        }
    }

    public String get(Long configId, String fromUserId) {
        if (configId == null || StringUtils.isBlank(fromUserId)) {
            return null;
        }
        Object v = redisUtil.hashGet(hashKey(configId), fromUserId);
        return v == null ? null : v.toString();
    }

    /**
     * 渠道删除或禁用时清理该渠道下所有会话 token。
     */
    public void clearForConfig(Long configId) {
        if (configId == null) {
            return;
        }
        redisUtil.deleteKey(hashKey(configId));
    }
}
