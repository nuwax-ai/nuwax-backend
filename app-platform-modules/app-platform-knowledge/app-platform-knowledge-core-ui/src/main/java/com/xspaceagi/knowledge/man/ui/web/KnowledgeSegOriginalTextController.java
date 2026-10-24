package com.xspaceagi.knowledge.man.ui.web;

import com.xspaceagi.knowledge.core.infra.rawsegmentoriginal.service.RawSegmentOriginalService;
import com.xspaceagi.knowledge.man.ui.web.base.BaseController;
import com.xspaceagi.system.sdk.operate.ActionType;
import com.xspaceagi.system.sdk.operate.OperationLogReporter;
import com.xspaceagi.system.sdk.operate.SystemEnum;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.xspaceagi.system.spec.enums.ResourceEnum.COMPONENT_LIB_QUERY_DETAIL;

/**
 * 原文对照接口
 * 根据分段ID查询所属文档的全部分段，用于原文对照功能
 */
@Tag(name = "知识库-原文对照接口")
@RestController
@RequestMapping("/api/knowledge/segOriginalText")
@Slf4j
public class KnowledgeSegOriginalTextController extends BaseController {

    @Resource
    private RawSegmentOriginalService rawSegmentOriginalService;

    @RequireResource(COMPONENT_LIB_QUERY_DETAIL)
    @OperationLogReporter(actionType = ActionType.QUERY, action = "原文对照查询", objectName = "知识库分段", systemCode = SystemEnum.KNOWLEDGE_CONFIG)
    @Operation(summary = "原文对照查询", description = "根据分段ID查询所属文档的全部分段，用于原文对照功能")
    @RequestMapping(path = "/list", method = RequestMethod.GET)
    public ReqResult<Map<String, Object>> listOriginalText(
            @Schema(description = "分段ID")
            Long segmentId,
            @Schema(description = "智能体ID")
            Long agentId) {

        log.info("原文对照查询，segmentId: {}, agentId: {}", segmentId, agentId);

        Map<String, Object> result = rawSegmentOriginalService.listOriginalTextBySegmentId(segmentId, agentId);

        return ReqResult.success(result);
    }
}
