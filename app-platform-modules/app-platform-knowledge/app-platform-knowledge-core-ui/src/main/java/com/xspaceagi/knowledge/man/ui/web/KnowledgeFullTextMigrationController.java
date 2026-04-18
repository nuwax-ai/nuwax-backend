package com.xspaceagi.knowledge.man.ui.web;

import com.xspaceagi.knowledge.core.application.service.IKnowledgeFullTextBatchSyncService;
import com.xspaceagi.knowledge.man.ui.web.base.BaseController;
import com.xspaceagi.system.domain.log.LogPrint;
import com.xspaceagi.system.domain.log.LogRecordPrint;
import com.xspaceagi.system.sdk.operate.ActionType;
import com.xspaceagi.system.sdk.operate.OperationLogReporter;
import com.xspaceagi.system.sdk.operate.SystemEnum;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库全文检索数据同步 Controller（基于 Quickwit）
 * 
 * @author system
 * @date 2025-03-31
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge/fulltext/migration")
@Tag(name = "知识库-全文检索数据同步")
public class KnowledgeFullTextMigrationController extends BaseController {
    
    @Resource
    private IKnowledgeFullTextBatchSyncService batchSyncService;
    
    @OperationLogReporter(
            actionType = ActionType.ADD,
        action = "同步所有知识库到全文检索", 
        objectName = "知识库全文检索同步", 
        systemCode = SystemEnum.KNOWLEDGE_CONFIG
    )
    @LogPrint(step = "知识库[全文检索]-同步所有知识库")
    @LogRecordPrint(content = "[知识库全文检索]-同步所有知识库")
    @Operation(
        summary = "同步所有知识库到 Quickwit（推荐）", 
        description = "将所有知识库的分段数据批量同步到 Quickwit 全文检索引擎。适合历史数据迁移和定期数据同步。"
    )
    @PostMapping("/sync-all")
    public ReqResult<SyncAllResult> syncAll() {
        log.info("========== 开始同步所有知识库到 Quickwit ==========");
        
        Long tenantId = getUser().getTenantId();
        long startTime = System.currentTimeMillis();
        
        try {
            // 使用带状态更新的迁移方法
            batchSyncService.syncAllUnsyncedKnowledgeBasesToQuickwit(tenantId);
            
            long costTime = System.currentTimeMillis() - startTime;
            
            SyncAllResult result = new SyncAllResult();
            result.setSuccess(true);
            result.setCostTimeMs(costTime);
            result.setMessage("所有未同步知识库同步完成");
            
            log.info("========== 所有知识库同步完成，Elapsed: {}ms ==========", costTime);
            
            return ReqResult.success(result);
            
        } catch (Exception e) {
            log.error("同步所有知识库失败", e);
            
            SyncAllResult result = new SyncAllResult();
            result.setSuccess(false);
            result.setCostTimeMs(System.currentTimeMillis() - startTime);
            result.setMessage("同步失败: " + e.getMessage());
            
            return ReqResult.error(e.getMessage());
        }
    }
    
    @OperationLogReporter(
        actionType = ActionType.ADD, 
        action = "同步单个知识库", 
        objectName = "知识库全文检索同步", 
        systemCode = SystemEnum.KNOWLEDGE_CONFIG
    )
    @LogPrint(step = "知识库[全文检索]-同步单个知识库")
    @LogRecordPrint(content = "[知识库全文检索]-同步单个知识库")
    @Operation(
        summary = "同步单个知识库到 Quickwit", 
        description = "将指定知识库的所有分段数据同步到 Quickwit 全文检索引擎"
    )
    @PostMapping("/sync/{kbId}")
    public ReqResult<SyncResult> syncKnowledgeBase(
            @Parameter(description = "知识库ID") @PathVariable Long kbId) {
        
        log.info("Starting knowledge base sync: kbId={}", kbId);
        
        Long tenantId = getUser().getTenantId();
        long startTime = System.currentTimeMillis();
        
        try {
            long syncedCount = batchSyncService.batchSyncKnowledgeBaseForMigration(kbId, tenantId);
            long costTime = System.currentTimeMillis() - startTime;
            
            SyncResult result = new SyncResult();
            result.setKbId(kbId);
            result.setSuccess(true);
            result.setSyncedCount(syncedCount);
            result.setCostTimeMs(costTime);
            result.setMessage("知识库同步成功");
            
            log.info("Knowledge base sync succeeded: kbId={}, syncedCount={}, costTime={}ms", 
                kbId, syncedCount, costTime);
            
            return ReqResult.success(result);
            
        } catch (Exception e) {
            log.error("Knowledge base sync failed: kbId={}", kbId, e);
            
            SyncResult result = new SyncResult();
            result.setKbId(kbId);
            result.setSuccess(false);
            result.setCostTimeMs(System.currentTimeMillis() - startTime);
            result.setMessage("同步失败: " + e.getMessage());
            
            return ReqResult.error(e.getMessage());
        }
    }
    
    @OperationLogReporter(
        actionType = ActionType.ADD, 
        action = "批量同步知识库", 
        objectName = "知识库全文检索同步", 
        systemCode = SystemEnum.KNOWLEDGE_CONFIG
    )
    @LogPrint(step = "知识库[全文检索]-批量同步知识库")
    @LogRecordPrint(content = "[知识库全文检索]-批量同步知识库")
    @Operation(
        summary = "批量同步知识库到 Quickwit", 
        description = "批量同步多个知识库的分段数据到 Quickwit"
    )
    @PostMapping("/batch-sync")
    public ReqResult<BatchSyncResult> batchSync(
            @Parameter(description = "知识库ID列表") @RequestBody List<Long> kbIds) {
        
        log.info("开始批量同步知识库: count={}", kbIds.size());
        
        Long tenantId = getUser().getTenantId();
        long startTime = System.currentTimeMillis();
        
        List<SyncResult> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;
        
        for (Long kbId : kbIds) {
            try {
                long syncedCount = batchSyncService.batchSyncKnowledgeBaseForMigration(kbId, tenantId);
                
                SyncResult result = new SyncResult();
                result.setKbId(kbId);
                result.setSuccess(true);
                result.setSyncedCount(syncedCount);
                result.setMessage("同步成功");
                results.add(result);
                
                successCount++;
                
                log.info("Knowledge base sync succeeded: kbId={}, syncedCount={}", kbId, syncedCount);
                
            } catch (Exception e) {
                SyncResult result = new SyncResult();
                result.setKbId(kbId);
                result.setSuccess(false);
                result.setMessage("同步失败: " + e.getMessage());
                results.add(result);
                
                failCount++;
                
                log.error("Knowledge base sync failed: kbId={}", kbId, e);
            }
        }
        
        long costTime = System.currentTimeMillis() - startTime;
        
        BatchSyncResult batchResult = new BatchSyncResult();
        batchResult.setTotalCount(kbIds.size());
        batchResult.setSuccessCount(successCount);
        batchResult.setFailCount(failCount);
        batchResult.setResults(results);
        batchResult.setCostTimeMs(costTime);
        
        log.info("批量同步完成: 总数={}, 成功={}, 失败={}, 耗时={}ms", 
            kbIds.size(), successCount, failCount, costTime);
        
        return ReqResult.success(batchResult);
    }
    
    @OperationLogReporter(
        actionType = ActionType.QUERY, 
        action = "验证同步结果", 
        objectName = "知识库全文检索同步", 
        systemCode = SystemEnum.KNOWLEDGE_CONFIG
    )
    @LogPrint(step = "知识库[全文检索]-验证同步结果")
    @LogRecordPrint(content = "[知识库全文检索]-验证同步结果")
    @Operation(
        summary = "验证同步结果", 
        description = "对比 MySQL 和 Quickwit 的数据，检查数据一致性"
    )
    @PostMapping("/validate")
    public ReqResult<ValidateResult> validate() {
        log.info("Starting sync result verification");
        
        Long tenantId = getUser().getTenantId();
        long startTime = System.currentTimeMillis();
        
        try {
            batchSyncService.validateSyncResult(tenantId);
            long costTime = System.currentTimeMillis() - startTime;
            
            ValidateResult result = new ValidateResult();
            result.setSuccess(true);
            result.setCostTimeMs(costTime);
            result.setMessage("验证完成，详情请查看日志");
            
            log.info("验证同步结果完成，Elapsed: {}ms", costTime);
            
            return ReqResult.success(result);
            
        } catch (Exception e) {
            log.error("验证同步结果失败", e);
            
            ValidateResult result = new ValidateResult();
            result.setSuccess(false);
            result.setCostTimeMs(System.currentTimeMillis() - startTime);
            result.setMessage("验证失败: " + e.getMessage());
            
            return ReqResult.error(e.getMessage());
        }
    }
    
    // ==================== 响应对象 ==================== //
    
    @Data
    public static class SyncAllResult {
        private Boolean success;
        private Long costTimeMs;
        private String message;
    }
    
    @Data
    public static class SyncResult {
        private Long kbId;
        private Boolean success;
        private Long syncedCount;
        private Long costTimeMs;
        private String message;
    }
    
    @Data
    public static class BatchSyncResult {
        private Integer totalCount;
        private Integer successCount;
        private Integer failCount;
        private List<SyncResult> results;
        private Long costTimeMs;
    }
    
    @Data
    public static class ValidateResult {
        private Boolean success;
        private Long costTimeMs;
        private String message;
    }
    
    @OperationLogReporter(
        actionType = ActionType.MODIFY, 
        action = "修复数据一致性", 
        objectName = "知识库全文检索同步", 
        systemCode = SystemEnum.KNOWLEDGE_CONFIG
    )
    @LogPrint(step = "知识库[全文检索]-修复数据一致性")
    @LogRecordPrint(content = "[知识库全文检索]-修复数据一致性")
    @Operation(
        summary = "修复知识库数据一致性", 
        description = "对比 MySQL 和 Quickwit 的数据，自动补推缺失的分段或删除多余的分段"
    )
    @PostMapping("/repair/{kbId}")
    public ReqResult<RepairResult> repairConsistency(
            @Parameter(description = "知识库ID") @PathVariable Long kbId) {
        
        log.info("开始修复数据一致性: kbId={}", kbId);
        
        Long tenantId = getUser().getTenantId();
        long startTime = System.currentTimeMillis();
        
        try {
            long repairedCount = batchSyncService.repairKnowledgeBaseConsistency(kbId, tenantId);
            long costTime = System.currentTimeMillis() - startTime;
            
            RepairResult result = new RepairResult();
            result.setKbId(kbId);
            result.setSuccess(true);
            result.setRepairedCount(repairedCount);
            result.setCostTimeMs(costTime);
            result.setMessage("数据一致性修复成功");
            
            log.info("数据一致性修复成功: kbId={}, repairedCount={}, costTime={}ms", 
                kbId, repairedCount, costTime);
            
            return ReqResult.success(result);
            
        } catch (Exception e) {
            log.error("数据一致性修复失败: kbId={}", kbId, e);
            
            RepairResult result = new RepairResult();
            result.setKbId(kbId);
            result.setSuccess(false);
            result.setCostTimeMs(System.currentTimeMillis() - startTime);
            result.setMessage("修复失败: " + e.getMessage());
            
            return ReqResult.error(e.getMessage());
        }
    }
    
    @Data
    public static class RepairResult {
        private Long kbId;
        private Boolean success;
        private Long repairedCount;
        private Long costTimeMs;
        private String message;
    }
    
}

