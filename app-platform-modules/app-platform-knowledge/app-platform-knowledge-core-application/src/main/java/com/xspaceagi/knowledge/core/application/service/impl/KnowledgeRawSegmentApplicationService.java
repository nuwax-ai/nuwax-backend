package com.xspaceagi.knowledge.core.application.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import org.springframework.stereotype.Service;

import com.google.common.collect.Lists;
import com.xspaceagi.knowledge.core.application.service.IKnowledgeFullTextSyncService;
import com.xspaceagi.knowledge.core.application.service.IKnowledgeRawSegmentApplicationService;
import com.xspaceagi.knowledge.core.spec.utils.ThreadTenantUtil;
import com.xspaceagi.knowledge.domain.model.KnowledgeRawSegmentModel;
import com.xspaceagi.knowledge.domain.service.IKnowledgeConfigDomainService;
import com.xspaceagi.knowledge.domain.service.IKnowledgeDocumentDomainService;
import com.xspaceagi.knowledge.domain.service.IKnowledgeQaSegmentDomainService;
import com.xspaceagi.knowledge.domain.service.IKnowledgeRawSegmentDomainService;
import com.xspaceagi.knowledge.domain.vectordb.VectorDBService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.tenant.thread.TenantRunnable;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class KnowledgeRawSegmentApplicationService implements IKnowledgeRawSegmentApplicationService {

    @Resource
    private IKnowledgeRawSegmentDomainService knowledgeRawSegmentDomainService;

    @Resource
    private IKnowledgeConfigDomainService knowledgeConfigDomainService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private IKnowledgeDocumentDomainService knowledgeDocumentDomainService;

    @Resource
    private IKnowledgeQaSegmentDomainService knowledgeQaSegmentDomainService;

    @Resource
    private ThreadTenantUtil threadTenantUtil;

    @Resource
    private VectorDBService vectorDBService;

    @Resource
    private IKnowledgeFullTextSyncService fullTextSyncService;

    @Override
    public KnowledgeRawSegmentModel queryOneInfoById(Long id) {
        return this.knowledgeRawSegmentDomainService.queryOneInfoById(id);
    }

    @Override
    public void deleteById(Long id, UserContext userContext) {

        var existObj = this.knowledgeRawSegmentDomainService.queryOneInfoById(id);
        if (Objects.isNull(existObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        // 校验用户和空间对应权限
        var spaceId = existObj.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        // 删除原始分段（包括数据库、QA、全文检索，在 Domain 层事务内处理）
        this.knowledgeRawSegmentDomainService.deleteById(id, userContext);
    }

    @Override
    public Long updateInfo(KnowledgeRawSegmentModel model, UserContext userContext) {
        var existObj = this.knowledgeRawSegmentDomainService.queryOneInfoById(model.getId());
        if (Objects.isNull(existObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        //数据正在向量化生成的过程中不能操作数据
        if(existObj.getQaStatus() != null && existObj.getQaStatus() != 1) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.knowledgeQaGenerationBusy);
        }
        // 校验用户和空间对应权限
        var spaceId = existObj.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        // 更新原始分段
        var id = this.knowledgeRawSegmentDomainService.updateInfo(model, userContext);

        // 查询更新后的分段信息
        var newRawSegment = this.knowledgeRawSegmentDomainService.queryOneInfoById(id);

        // 同步更新全文检索数据
        try {
            fullTextSyncService.updateSegmentText(id, newRawSegment.getRawTxt(), userContext);
            log.info("原始分段更新成功，已同步更新全文检索数据: rawId={}", id);
        } catch (Exception e) {
            log.error("更新原始分段全文检索数据失败: rawId={}", id, e);
        }

        // 处理问答生成和向量化
        processQaGenerationAndVectorization(id, newRawSegment, userContext);

        return id;
    }

    /**
     * 处理问答生成和向量化的私有方法
     * 
     * @param rawSegmentId    原始分段ID
     * @param existingSegment 现有的分段对象
     * @param userContext     用户上下文
     */
    private void processQaGenerationAndVectorization(Long rawSegmentId, KnowledgeRawSegmentModel existingSegment,
            UserContext userContext) {
        // 根据分段id,删除对应的问答,以及问答的向量化
        this.knowledgeQaSegmentDomainService.deleteByRawId(rawSegmentId);

        // 重新触发分段的对应问答生成,比较耗时,用线程的方式提交运行
        TenantRunnable runnable = new TenantRunnable(() -> {
            var segments = Lists.newArrayList(existingSegment);
            this.knowledgeDocumentDomainService.generateForQaByRawSegment(segments, userContext);
        });

        threadTenantUtil.obtainCommonExecutor().execute(runnable);
    }

    @Override
    public Long addInfo(KnowledgeRawSegmentModel model, UserContext userContext) {

        // 查询基础配置,补全基础信息
        var knowledgeConfig = this.knowledgeConfigDomainService.queryOneInfoById(model.getKbId());
        if (Objects.isNull(knowledgeConfig)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        var spaceId = knowledgeConfig.getSpaceId();
        model.setSpaceId(spaceId);

        spacePermissionService.checkSpaceUserPermission(spaceId);

        // 新增原始分段
        var id = this.knowledgeRawSegmentDomainService.addInfo(model, userContext);
        
        // 查询新增的分段
        var newRawSegment = this.knowledgeRawSegmentDomainService.queryOneInfoById(id);

        // 同步推送到全文检索（异步）
        try {
            TenantRunnable syncRunnable = new TenantRunnable(() -> {
                try {
                    // 注意：这里需要通过 syncDocumentToQuickwit 来同步，因为它需要查询所有分段并批量推送
                    // 这里为了简化，只同步当前分段，实际应用中可能需要根据 docId 来同步所有分段
                    fullTextSyncService.syncDocumentToQuickwit(newRawSegment.getDocId(), userContext);
                    log.info("原始分段新增成功，已同步推送到全文检索: rawId={}, docId={}", id, newRawSegment.getDocId());
                } catch (Exception e) {
                    log.error("新增原始分段同步全文检索失败: rawId={}", id, e);
                }
            });
            threadTenantUtil.obtainCommonExecutor().execute(syncRunnable);
        } catch (Exception e) {
            log.error("提交全文检索同步任务失败: rawId={}", id, e);
        }

        // 触发分段对应的 问答生成,以及 问答向量化
        processQaGenerationAndVectorization(id, newRawSegment, userContext);
        return id;
    }

    @Override
    public List<KnowledgeRawSegmentModel> queryListForPendingQaAndGenerateQa(Integer days, Integer pageSize,
            Integer pageNum) {
        log.info("开始生成问答,查询待生成问答的分段信息,查询最近{}天,每页大小{},页码{}", days, pageSize, pageNum);
        var list = TenantFunctions.callWithIgnoreCheck(() -> {
            return this.knowledgeRawSegmentDomainService.queryListForPendingQa(days, pageSize, pageNum);
        });
        var size = list.size();

        log.info("查询待生成问答的分段信息,查询最近{}天,每页大小{},页码{},共{}条", days, pageSize, pageNum, size);
        if (size > 0) {
            var userContext = UserContext.builder()
                    .userId(0L)
                    .userName("系统")
                    .build();

            // 按照租户id分组:tenantId
            Map<Long, List<KnowledgeRawSegmentModel>> tenantIdGroup = list.stream()
                    .collect(Collectors.groupingBy(KnowledgeRawSegmentModel::getTenantId));

            for (Map.Entry<Long, List<KnowledgeRawSegmentModel>> entry : tenantIdGroup.entrySet()) {
                Long tenantId = entry.getKey();
                List<KnowledgeRawSegmentModel> segmentList = entry.getValue();
                try {
                    // 设置租户id
                    RequestContext.setThreadTenantId(tenantId);
                    this.knowledgeDocumentDomainService.generateForQaByRawSegment(segmentList, userContext);
                } finally {
                    RequestContext.remove();
                }

            }

        }
        log.info("生成问答完成,查询待生成问答的分段信息,查询最近{}天,每页大小{},页码{}", days, pageSize, pageNum);
        return list;
    }

}
