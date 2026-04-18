package com.xspaceagi.agent.web.ui.controller.manage;

import com.xspaceagi.agent.core.infra.dao.mapper.ConversationMessageMapper;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "消息管理-用户智能体消息统计")
@RestController
@RequestMapping("/api/system/userAgent/message")
public class UserMessageStatController {

    @Resource
    private ConversationMessageMapper conversationMessageMapper;

    @Operation(summary = "查询用户智能体消息统计")
    @GetMapping("/count")
    public ReqResult<List<Map<String, Long>>> userAgentMessageCount(@RequestParam Long userId) {
        List<Map<String, Long>> maps = conversationMessageMapper.countUserAgentMessages(userId);
        maps.forEach(map -> map.forEach((k, v) -> map.put(k, v == null ? 0 : v / 2)));
        return ReqResult.success(maps);
    }
}
