package com.xspaceagi.knowledge.man.ui.web;

import com.xspaceagi.knowledge.core.application.service.impl.KnowledgeFullTextBatchSyncService;
import com.xspaceagi.knowledge.domain.model.fulltext.FullTextStatsModel;
import com.xspaceagi.knowledge.man.ui.web.base.BaseController;
import com.xspaceagi.knowledge.man.ui.web.dto.SyncResultVo;
import com.xspaceagi.knowledge.sdk.request.KnowledgeFullTextSearchRequestVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeFullTextSearchResponseVo;
import com.xspaceagi.knowledge.sdk.sevice.IKnowledgeFullTextSearchRpcService;
import com.xspaceagi.system.domain.log.LogPrint;
import com.xspaceagi.system.domain.log.LogRecordPrint;
import com.xspaceagi.system.sdk.operate.ActionType;
import com.xspaceagi.system.sdk.operate.OperationLogReporter;
import com.xspaceagi.system.sdk.operate.SystemEnum;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 知识库全文检索 Controller
 * 
 * @author system
 * @date 2025-03-31
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge/fulltext")
@Tag(name = "知识库-全文检索")
public class KnowledgeFullTextSearchController extends BaseController {
    
    @Resource
    private IKnowledgeFullTextSearchRpcService fullTextSearchRpcService;

    @Resource
    private KnowledgeFullTextBatchSyncService batchSyncService;
    
    @OperationLogReporter(
            actionType = ActionType.QUERY,
        action = "全文检索", 
        objectName = "知识库全文检索", 
        systemCode = SystemEnum.KNOWLEDGE_CONFIG
    )
    @LogPrint(step = "知识库[全文检索]-检索查询")
    @LogRecordPrint(content = "[知识库全文检索]-检索查询")
    @Operation(
        summary = "全文检索", 
        description = "基于BM25算法的全文关键词检索。支持传入 kbIds（知识库ID列表）和 queryText。"
    )
    @PostMapping("/search")
    public ReqResult<KnowledgeFullTextSearchResponseVo> search(
            @Valid @RequestBody KnowledgeFullTextSearchRequestVo request) {
        
        log.info("全文检索请求: kbIds={}, queryText={}", request.getKbIds(), request.getQueryText());
        
        KnowledgeFullTextSearchResponseVo response = 
            fullTextSearchRpcService.search(request);
        
        log.info("全文检索成功: kbIds={}, resultCount={}, costTimeMs={}", 
            request.getKbIds(), response.getResults().size(), response.getCostTimeMs());
        
        return ReqResult.success(response);
    }

    @OperationLogReporter(
        actionType = ActionType.QUERY,
        action = "获取统计信息",
        objectName = "知识库全文检索",
        systemCode = SystemEnum.KNOWLEDGE_CONFIG
    )
    @LogPrint(step = "知识库[全文检索]-获取统计信息")
    @Operation(
        summary = "获取统计信息",
        description = "获取全文检索的统计信息，包括文档数量、分段数量等"
    )
    @GetMapping("/stats")
    public ReqResult<FullTextStatsModel> getStats(
            @RequestParam(required = false) Long kbId) {

        log.info("获取统计信息: kbId={}", kbId);

        UserContext userContext = this.getUser();
        FullTextStatsModel stats = batchSyncService.getStats(kbId, userContext);

        return ReqResult.success(stats);
    }

    @OperationLogReporter(
        actionType = ActionType.MODIFY,
        action = "全量同步所有知识库",
        objectName = "知识库全文检索",
        systemCode = SystemEnum.KNOWLEDGE_CONFIG
    )
    @LogPrint(step = "知识库[全文检索]-全量同步")
    @LogRecordPrint(content = "[知识库全文检索]-全量同步所有知识库")
    @Operation(
        summary = "全量同步所有知识库",
        description = "将所有知识库的分段数据同步到 Quickwit（上线必需操作）"
    )
    @PostMapping("/sync/all")
    public ReqResult<SyncResultVo> syncAll() {

        log.info("开始全量同步所有知识库");

        UserContext userContext = this.getUser();
        Long tenantId = userContext.getTenantId();

        try {
            // 使用带状态更新的迁移方法
            batchSyncService.syncAllUnsyncedKnowledgeBasesToQuickwit(tenantId);

            SyncResultVo result = new SyncResultVo();
            result.setSuccess(true);
            result.setMessage("全量同步完成，请查看日志了解详情");

            return ReqResult.success(result);

        } catch (Exception e) {
            log.error("全量同步失败", e);

            SyncResultVo result = new SyncResultVo();
            result.setSuccess(false);
            result.setMessage("同步失败: " + e.getMessage());

            return ReqResult.error("同步失败: " + e.getMessage());
        }
    }

    @OperationLogReporter(
        actionType = ActionType.MODIFY,
        action = "同步指定知识库",
        objectName = "知识库全文检索",
        systemCode = SystemEnum.KNOWLEDGE_CONFIG
    )
    @LogPrint(step = "知识库[全文检索]-同步指定知识库")
    @LogRecordPrint(content = "[知识库全文检索]-同步指定知识库")
    @Operation(
        summary = "同步指定知识库",
        description = "将指定知识库的分段数据同步到 Quickwit"
    )
    @PostMapping("/sync/kb/{kbId}")
    public ReqResult<SyncResultVo> syncKnowledgeBase(@PathVariable Long kbId) {

        log.info("Starting knowledge base sync: kbId={}", kbId);

        UserContext userContext = this.getUser();
        Long tenantId = userContext.getTenantId();

        try {
            long count = batchSyncService.batchSyncKnowledgeBaseForMigration(kbId, tenantId);

            SyncResultVo result = new SyncResultVo();
            result.setSuccess(true);
            result.setMessage("同步完成，共同步 " + count + " 个分段");
            result.setSyncedCount(count);

            return ReqResult.success(result);

        } catch (Exception e) {
            log.error("Knowledge base sync failed: kbId={}", kbId, e);

            SyncResultVo result = new SyncResultVo();
            result.setSuccess(false);
            result.setMessage("同步失败: " + e.getMessage());

            return ReqResult.error("同步失败: " + e.getMessage());
        }
    }

    @OperationLogReporter(
        actionType = ActionType.QUERY,
        action = "验证同步结果",
        objectName = "知识库全文检索",
        systemCode = SystemEnum.KNOWLEDGE_CONFIG
    )
    @LogPrint(step = "知识库[全文检索]-验证同步结果")
    @LogRecordPrint(content = "[知识库全文检索]-验证同步结果")
    @Operation(
        summary = "验证同步结果",
        description = "验证 MySQL 和 Quickwit 的数据一致性"
    )
    @PostMapping("/validate")
    public ReqResult<SyncResultVo> validateSync() {

        log.info("Starting sync result verification");

        UserContext userContext = this.getUser();
        Long tenantId = userContext.getTenantId();

        try {
            batchSyncService.validateSyncResult(tenantId);

            SyncResultVo result = new SyncResultVo();
            result.setSuccess(true);
            result.setMessage("验证完成，请查看日志了解详情");

            return ReqResult.success(result);

        } catch (Exception e) {
            log.error("验证失败", e);

            SyncResultVo result = new SyncResultVo();
            result.setSuccess(false);
            result.setMessage("验证失败: " + e.getMessage());

            return ReqResult.error("验证失败: " + e.getMessage());
        }
    }
    
}

