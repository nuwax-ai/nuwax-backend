package com.xspaceagi.knowledge.core.application.service.impl;

import com.xspaceagi.knowledge.core.adapter.client.dto.PushResult;
import com.xspaceagi.knowledge.core.application.translator.KnowledgeFullTextSearchTranslator;
import com.xspaceagi.knowledge.core.spec.enums.FulltextSyncStatusEnum;
import com.xspaceagi.knowledge.core.spec.enums.RawTextFulltextSyncStatusEnum;
import com.xspaceagi.knowledge.domain.model.KnowledgeConfigModel;
import com.xspaceagi.knowledge.domain.model.KnowledgeRawSegmentModel;
import com.xspaceagi.knowledge.domain.model.fulltext.FullTextStatsModel;
import com.xspaceagi.knowledge.domain.model.fulltext.RawSegmentFullTextModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeConfigRepository;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeRawSegmentRepository;
import com.xspaceagi.knowledge.domain.service.IKnowledgeFullTextSearchDomainService;
import com.xspaceagi.system.spec.common.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库全文检索批量同步服务实现（存量数据同步）
 * 
 * @author system
 * @date 2025-03-31
 */
@Slf4j
@Service
public class KnowledgeFullTextBatchSyncService implements com.xspaceagi.knowledge.core.application.service.IKnowledgeFullTextBatchSyncService {

    @Resource
    private IKnowledgeConfigRepository knowledgeConfigRepository;

    @Resource
    private IKnowledgeRawSegmentRepository rawSegmentRepository;

    @Resource
    private IKnowledgeFullTextSearchDomainService fullTextSearchDomainService;

    @Resource
    private KnowledgeFullTextSearchTranslator translator;

    @Resource
    private KnowledgeFullTextSearchApplicationService fullTextSearchApplicationService;

    /**
     * 获取统计信息（委托给 Application 服务）
     * 
     * @param kbId 知识库ID
     * @param userContext 用户上下文
     * @return 统计信息
     */
    public FullTextStatsModel getStats(Long kbId, UserContext userContext) {
        return fullTextSearchApplicationService.getStats(kbId, userContext);
    }

    /**
     * 验证同步结果
     * 
     * @param tenantId 租户ID
     */
    public void validateSyncResult(Long tenantId) {
        log.info("Starting sync result verification: tenantId={}", tenantId);

        // 1. 获取所有知识库
        List<Long> kbIds = knowledgeConfigRepository.queryAllKbIds();

        // 2. 统计 MySQL 中的数据
        Map<Long, Long> mysqlStats = new HashMap<>();
        for (Long kbId : kbIds) {
            Long count = rawSegmentRepository.countByKbId(kbId);
            if (count != null && count > 0) {
                mysqlStats.put(kbId, count);
            }
        }

        // 3. 统计 Quickwit 中的数据
        Map<Long, Long> quickwitStats = new HashMap<>();
        for (Long kbId : mysqlStats.keySet()) {
            try {
                KnowledgeConfigModel config = knowledgeConfigRepository.queryOneInfoById(kbId);
                if (config != null) {
                    FullTextStatsModel stats = fullTextSearchDomainService.getStats(
                        tenantId, 
                        kbId, 
                        config.getSpaceId()
                    );
                    quickwitStats.put(kbId, stats.getTotalSegments());
                }
            } catch (Exception e) {
                log.error("Quickwit stats failed for KB {}", kbId, e);
            }
        }

        // 4. 对比结果
        boolean allMatch = true;
        for (Map.Entry<Long, Long> entry : mysqlStats.entrySet()) {
            Long kbId = entry.getKey();
            Long mysqlCount = entry.getValue();
            Long quickwitCount = quickwitStats.get(kbId);

            if (quickwitCount == null || !mysqlCount.equals(quickwitCount)) {
                log.warn("Data mismatch: kbId={}, MySQL={}, Quickwit={}", 
                    kbId, mysqlCount, quickwitCount);
                allMatch = false;
            } else {
                log.info("Data match: kbId={}, count={}", kbId, mysqlCount);
            }
        }

        if (allMatch) {
            log.info("Sync verify OK: all KBs consistent");
        } else {
            log.error("Sync verify failed: inconsistent KBs");
        }
    }

    /**
     * 同步所有未同步的知识库到 Quickwit（迁移专用）
     * 
     * @param tenantId 租户ID
     */
    @Override
    public void syncAllUnsyncedKnowledgeBasesToQuickwit(Long tenantId) {
        log.debug("Sync all unsynced KBs: tenantId={}", tenantId);
        
        // 1. 查询未同步的知识库
        List<KnowledgeConfigModel> unsyncedKbs = knowledgeConfigRepository.queryUnsyncedKnowledgeBases(tenantId);
        log.info("Found {} unsynced KBs", unsyncedKbs.size());
        
        int successCount = 0;
        int failCount = 0;
        StringBuilder errorMessages = new StringBuilder();
        
        // 2. 逐个同步
        for (KnowledgeConfigModel kb : unsyncedKbs) {
            try {
                long syncedCount = batchSyncKnowledgeBaseForMigration(kb.getId(), tenantId);
                log.info("Knowledge base sync succeeded: kbId={}, syncedCount={}", kb.getId(), syncedCount);
                successCount++;
            } catch (Exception e) {
                log.error("Knowledge base sync failed: kbId={}, name={}", kb.getId(), kb.getName(), e);
                // 标记为同步失败
                knowledgeConfigRepository.updateFulltextSyncStatus(kb.getId(), FulltextSyncStatusEnum.SYNC_FAILED.getCode(), 0L);
                failCount++;
                
                // 收集错误信息
                if (errorMessages.length() > 0) {
                    errorMessages.append("; ");
                }
                errorMessages.append(String.format("知识库[%s]同步失败: %s", 
                    kb.getName(), e.getMessage()));
            }
        }
        
        log.debug("All KB sync done: total={}, ok={}, failed={}",
            unsyncedKbs.size(), successCount, failCount);
        
        // 如果有失败，抛出异常
        if (failCount > 0) {
            throw new RuntimeException(String.format(
                "批量同步失败: 总数=%d, 成功=%d, 失败=%d。错误详情: %s", 
                unsyncedKbs.size(), successCount, failCount, errorMessages.toString()
            ));
        }
    }

    /**
     * 同步单个知识库的所有分段到 Quickwit（迁移专用）
     * 
     * @param kbId 知识库ID
     * @param tenantId 租户ID
     * @return 同步的分段数量
     */
    @Override
    public long batchSyncKnowledgeBaseForMigration(Long kbId, Long tenantId) {
        log.info("Starting knowledge base sync: kbId={}, tenantId={}", kbId, tenantId);
        
        // 1. 检查知识库是否存在
        KnowledgeConfigModel config = knowledgeConfigRepository.queryOneInfoById(kbId);
        if (config == null) {
            throw new IllegalArgumentException("Knowledge base does not exist: kbId=" + kbId);
        }
        

        // 3. 更新状态为"同步中"
        knowledgeConfigRepository.updateFulltextSyncStatus(kbId, FulltextSyncStatusEnum.SYNCHRONIZING.getCode(), 0L);
        
        try {
            long totalSynced = 0;
            int offset = 0;
            int batchSize = 1000;
            
            while (true) {
                // 4. 分页查询未同步的分段
                List<KnowledgeRawSegmentModel> segments = 
                    rawSegmentRepository.queryUnsyncedSegments(kbId, offset, batchSize);
                
                if (segments.isEmpty()) {
                    break;
                }
                
                log.info("Batch: kbId={}, offset={}, size={}", kbId, offset, segments.size());
                
                // 5. 分离空文本和非空文本的分段
                List<Long> emptyTextSegmentIds = segments.stream()
                    .filter(segment -> !StringUtils.hasText(segment.getRawTxt()))
                    .map(KnowledgeRawSegmentModel::getId)
                    .collect(Collectors.toList());
                
                List<RawSegmentFullTextModel> fullTextModels = segments.stream()
                    .filter(segment -> StringUtils.hasText(segment.getRawTxt()))
                    .map(segment -> translator.toFullTextModel(segment, tenantId, config.getSpaceId()))
                    .collect(Collectors.toList());
                
                // 6. 处理空文本分段：直接标记为已同步
                if (!emptyTextSegmentIds.isEmpty()) {
                    rawSegmentRepository.batchUpdateSyncStatus(emptyTextSegmentIds, RawTextFulltextSyncStatusEnum.SYNCED.getCode());
                    log.info("Empty segments marked synced: kbId={}, count={}", kbId, emptyTextSegmentIds.size());
                }
                
                // 7. 处理非空文本分段：推送到 Quickwit
                if (!fullTextModels.isEmpty()) {
                    PushResult pushResult =
                        fullTextSearchDomainService.pushSegments(fullTextModels);
                    
                    // 8. 根据 success_raw_ids 精确更新同步状态
                    if (pushResult != null && !org.springframework.util.CollectionUtils.isEmpty(pushResult.getSuccessRawIds())) {
                        // 直接使用 Long 类型，无需转换
                        List<Long> successRawIds = pushResult.getSuccessRawIds();
                        rawSegmentRepository.batchUpdateSyncStatus(successRawIds, RawTextFulltextSyncStatusEnum.SYNCED.getCode());
                        
                        totalSynced += pushResult.getIndexedCount();
                        
                        log.info("Batch sync status: kbId={}, pushed={}, ok={}", 
                            kbId, fullTextModels.size(), successRawIds.size());
                    } else {
                        log.warn("Batch push empty: kbId={}, segmentCount={}", kbId, fullTextModels.size());
                    }
                }
                
                offset += batchSize;
                
                log.info("Batch sync done: kbId={}, batchSize={}, totalSynced={}", 
                    kbId, segments.size(), totalSynced);
            }
            
            // 8. 检查是否所有分段都已同步完成
            Long unsyncedCount = rawSegmentRepository.countUnsyncedByKbId(kbId);
            if (unsyncedCount != null && unsyncedCount > 0) {
                // 仍有未同步的分段，更新为部分同步状态（可选：使用状态码1表示同步中，或保持原状态）
                log.warn("KB still has unsynced: kbId={}, unsyncedCount={}, totalSynced={}", 
                    kbId, unsyncedCount, totalSynced);
                knowledgeConfigRepository.updateFulltextSyncStatus(kbId, FulltextSyncStatusEnum.SYNCHRONIZING.getCode(), totalSynced);
            } else {
                // 所有分段都已同步完成，更新知识库状态为"已同步"
                knowledgeConfigRepository.updateFulltextSyncStatus(kbId, FulltextSyncStatusEnum.SYNCED.getCode(), totalSynced);
                log.info("KB all segments synced: kbId={}, totalSynced={}", kbId, totalSynced);
            }
            
            log.info("KB sync flow done: kbId={}, totalSynced={}", kbId, totalSynced);
            return totalSynced;
            
        } catch (Exception e) {
            // 同步失败，更新状态
            knowledgeConfigRepository.updateFulltextSyncStatus(kbId, FulltextSyncStatusEnum.SYNC_FAILED.getCode(), 0L);
            log.error("Knowledge base sync failed: kbId={}", kbId, e);
            throw e;
        }
    }

    /**
     * 补偿同步：修复指定知识库的数据不一致
     * 
     * @param kbId 知识库ID
     * @param tenantId 租户ID
     * @return 修复的分段数量
     */
    @Override
    public long repairKnowledgeBaseConsistency(Long kbId, Long tenantId) {
        log.info("Repair consistency start: kbId={}, tenantId={}", kbId, tenantId);
        
        // 0. 获取知识库配置
        KnowledgeConfigModel config = knowledgeConfigRepository.queryOneInfoById(kbId);
        if (config == null) {
            throw new IllegalArgumentException("Knowledge base does not exist: kbId=" + kbId);
        }
        
        // 1. 查询 MySQL 中的所有分段ID
        List<Long> mysqlSegmentIds = rawSegmentRepository.queryAllSegmentIds(kbId);
        log.info("MySQL segment count: kbId={}, count={}", kbId, mysqlSegmentIds.size());
        
        // 2. 查询 Quickwit 中的所有分段ID
        List<Long> quickwitSegmentIds = fullTextSearchDomainService.queryAllSegmentIds(
            kbId, tenantId, config.getSpaceId()
        );
        log.info("Quickwit segment count: kbId={}, count={}", kbId, quickwitSegmentIds.size());
        
        // 3. 找出 MySQL 有但 Quickwit 没有的（需要补推）
        java.util.Set<Long> quickwitSet = new java.util.HashSet<>(quickwitSegmentIds);
        List<Long> missingInQuickwit = mysqlSegmentIds.stream()
            .filter(id -> !quickwitSet.contains(id))
            .collect(Collectors.toList());
        
        // 4. 找出 Quickwit 有但 MySQL 没有的（需要删除）
        java.util.Set<Long> mysqlSet = new java.util.HashSet<>(mysqlSegmentIds);
        List<Long> extraInQuickwit = quickwitSegmentIds.stream()
            .filter(id -> !mysqlSet.contains(id))
            .collect(Collectors.toList());
        
        long repairedCount = 0;
        
        // 5. 补推缺失的分段
        if (!missingInQuickwit.isEmpty()) {
            log.info("Found {} missing segments, backfilling", missingInQuickwit.size());
            
            // 分批处理，避免一次性加载太多数据
            int batchSize = 1000;
            for (int i = 0; i < missingInQuickwit.size(); i += batchSize) {
                int end = Math.min(i + batchSize, missingInQuickwit.size());
                List<Long> batchIds = missingInQuickwit.subList(i, end);
                
                // 查询分段详情
                List<KnowledgeRawSegmentModel> segments = 
                    rawSegmentRepository.queryListInfoByIds(batchIds);
                
                // 分离空文本和非空文本的分段
                List<Long> emptyTextSegmentIds = segments.stream()
                    .filter(segment -> !StringUtils.hasText(segment.getRawTxt()))
                    .map(KnowledgeRawSegmentModel::getId)
                    .collect(Collectors.toList());
                
                List<RawSegmentFullTextModel> fullTextModels = segments.stream()
                    .filter(segment -> StringUtils.hasText(segment.getRawTxt()))
                    .map(segment -> translator.toFullTextModel(segment, tenantId, config.getSpaceId()))
                    .collect(Collectors.toList());
                
                // 处理空文本分段：直接标记为已同步
                if (!emptyTextSegmentIds.isEmpty()) {
                    rawSegmentRepository.batchUpdateSyncStatus(emptyTextSegmentIds, RawTextFulltextSyncStatusEnum.SYNCED.getCode());
                    log.info("Backfill batch empty-text marked: batch={}/{}, count={}", 
                        (i / batchSize + 1), (missingInQuickwit.size() + batchSize - 1) / batchSize,
                        emptyTextSegmentIds.size());
                }
                
                // 处理非空文本分段：推送到 Quickwit
                if (!fullTextModels.isEmpty()) {
                    com.xspaceagi.knowledge.core.adapter.client.dto.PushResult pushResult = 
                        fullTextSearchDomainService.pushSegments(fullTextModels);
                    
                    // 根据 success_raw_ids 更新同步状态
                    if (pushResult != null && !CollectionUtils.isEmpty(pushResult.getSuccessRawIds())) {
                        // 直接使用 Long 类型，无需转换
                        List<Long> successRawIds = pushResult.getSuccessRawIds();
                        rawSegmentRepository.batchUpdateSyncStatus(successRawIds, RawTextFulltextSyncStatusEnum.SYNCED.getCode());
                        
                        repairedCount += pushResult.getIndexedCount();
                        
                        log.info("Backfill batch done: batch={}/{}, indexed={}, success={}", 
                            (i / batchSize + 1), (missingInQuickwit.size() + batchSize - 1) / batchSize, 
                            pushResult.getIndexedCount(), successRawIds.size());
                    }
                }
            }
            
            log.info("补推完成: 总数={}", missingInQuickwit.size());
        }
        
        // 6. 删除多余的分段
        if (!extraInQuickwit.isEmpty()) {
            log.info("发现 {} 个多余的分段，开始删除", extraInQuickwit.size());
            
            // 分批删除
            int batchSize = 1000;
            for (int i = 0; i < extraInQuickwit.size(); i += batchSize) {
                int end = Math.min(i + batchSize, extraInQuickwit.size());
                List<Long> batchIds = extraInQuickwit.subList(i, end);
                
                Long deletedCount = fullTextSearchDomainService.deleteByRawIds(batchIds, tenantId);
                repairedCount += deletedCount;
                log.info("删除批次完成: batch={}/{}, deleted={}", 
                    (i / batchSize + 1), (extraInQuickwit.size() + batchSize - 1) / batchSize, deletedCount);
            }
            
            log.info("删除完成: 总数={}", extraInQuickwit.size());
        }
        
        log.info("数据一致性修复完成: kbId={}, 补推={}, 删除={}, 总修复={}", 
            kbId, missingInQuickwit.size(), extraInQuickwit.size(), repairedCount);
        
        return repairedCount;
    }
}
