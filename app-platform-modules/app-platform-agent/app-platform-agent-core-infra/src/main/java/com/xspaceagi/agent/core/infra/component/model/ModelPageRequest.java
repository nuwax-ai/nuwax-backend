package com.xspaceagi.agent.core.infra.component.model;

import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

@Component
public class ModelPageRequest {

    @Resource
    private RedisUtil redisUtil;

    public String getPageRequestResult(String requestId, String dataType) {
        // Read content from redis until it's not null, 30 seconds timeout
        long start = System.currentTimeMillis();
        Object val = redisUtil.get(buildKey(requestId));
        while (val == null) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                // ignore
            }
            if (System.currentTimeMillis() - start > 30000) {
                break;
            }
            val = redisUtil.get(buildKey(requestId));
        }
        return val == null ? "Failed to retrieve page data" : val.toString();
    }

    public void setPageRequestResult(String requestId, String result) {
        redisUtil.set(buildKey(requestId), result, 5); // 5 seconds expiration
    }

    private String buildKey(String requestId) {
        return "model.page.request:" + requestId;
    }
}
