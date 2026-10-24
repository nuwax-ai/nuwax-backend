package com.xspaceagi.connector.ui.web.controller;

import com.xspaceagi.system.spec.dto.ReqResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 连接器模块健康检查（S1 骨架验收端点）。
 */
@RestController
@RequestMapping("/api/connector")
public class ConnectorHealthController {

    @GetMapping("/health")
    public ReqResult<Map<String, Object>> health() {
        Map<String, Object> info = new HashMap<>();
        info.put("module", "app-platform-connector");
        info.put("status", "up");
        return ReqResult.success(info);
    }
}
