package com.xspaceagi.agent.web.ui.controller.api;

import com.xspaceagi.agent.core.infra.component.knowledge.accuracytest.AccuracyTestService;
import com.xspaceagi.agent.core.infra.component.knowledge.accuracytest.dto.AccuracyTestRecordRequest;
import com.xspaceagi.agent.core.infra.component.knowledge.accuracytest.dto.AccuracyTestSearchRequest;
import com.xspaceagi.agent.core.infra.component.knowledge.accuracytest.vo.AccuracyTestRecordResponse;
import com.xspaceagi.agent.core.infra.component.knowledge.accuracytest.vo.AccuracyTestSearchResponse;
import com.xspaceagi.agent.core.infra.component.knowledge.accuracytest.vo.DocumentListResponse;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 命中测试接口
 */
@Tag(name = "命中测试接口")
@RestController
@RequestMapping("/api/knowledge/accuracytest")
@Slf4j
public class AccuracyTestController {

    @Resource
    private AccuracyTestService accuracyTestService;

    /**
     * 保存测试记录
     */
    @Operation(summary = "保存命中测试记录")
    @PostMapping("/record")
    public ReqResult<Long> saveRecord(@RequestBody @Valid AccuracyTestRecordRequest request) {
        try {
            UserDto currentUser = (UserDto) RequestContext.get().getUser();
            Long recordId = accuracyTestService.saveTestRecord(request, currentUser);
            return ReqResult.success(recordId);
        } catch (Exception e) {
            log.error("保存测试记录失败", e);
            return ReqResult.error(e.getMessage());
        }
    }

    /**
     * 查询测试历史
     */
    @Operation(summary = "查询命中测试历史")
    @GetMapping("/history")
    public ReqResult<List<AccuracyTestRecordResponse.TestHistoryItem>> getHistory(
            @RequestParam Long knowledgeBaseId) {
        try {
            UserDto currentUser = (UserDto) RequestContext.get().getUser();
            List<AccuracyTestRecordResponse.TestHistoryItem> history = accuracyTestService.getTestHistory(knowledgeBaseId, currentUser);
            return ReqResult.success(history);
        } catch (Exception e) {
            log.error("查询测试历史失败", e);
            return ReqResult.error(e.getMessage());
        }
    }

    /**
     * 执行搜索测试
     */
    @Operation(summary = "执行命中搜索测试")
    @PostMapping("/search")
    public ReqResult<AccuracyTestSearchResponse> performSearch(@RequestBody @Valid AccuracyTestSearchRequest request) {
        try {
            AccuracyTestSearchResponse response = accuracyTestService.performSearch(request);
            return ReqResult.success(response);
        } catch (Exception e) {
            log.error("执行搜索测试失败", e);
            return ReqResult.error(e.getMessage());
        }
    }

    /**
     * 获取文档列表
     */
    @Operation(summary = "获取知识库文档列表")
    @GetMapping("/documents")
    public ReqResult<List<DocumentListResponse.DocumentItem>> getDocuments(
            @RequestParam Long knowledgeBaseId) {
        try {
            List<DocumentListResponse.DocumentItem> documents = accuracyTestService.getDocumentList(knowledgeBaseId);
            return ReqResult.success(documents);
        } catch (Exception e) {
            log.error("获取文档列表失败", e);
            return ReqResult.error(e.getMessage());
        }
    }
}