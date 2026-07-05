package com.xspaceagi.pay.application.support;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.pay.sdk.support.PayOrderExtKeys;
import com.xspaceagi.pay.sdk.enums.PayClientScene;
import java.util.HashMap;
import java.util.Map;

public final class PayOrderExtSupport {

    private PayOrderExtSupport() {}

    public static String mergePayClientScene(String extJson, PayClientScene scene) {
        if (scene == null) {
            return extJson;
        }
        Map<String, Object> map = parseExt(extJson);
        map.put(PayOrderExtKeys.PAY_CLIENT_SCENE, scene.name());
        return JSON.toJSONString(map);
    }

    public static Map<String, Object> parseExt(String extJson) {
        if (extJson == null || extJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> parsed = JSON.parseObject(extJson);
            return parsed != null ? parsed : new HashMap<>();
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
