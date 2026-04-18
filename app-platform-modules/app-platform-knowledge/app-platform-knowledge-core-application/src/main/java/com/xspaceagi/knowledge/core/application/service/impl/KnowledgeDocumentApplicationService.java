package com.xspaceagi.knowledge.core.application.service.impl;

import java.util.List;
import java.util.Objects;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.xspaceagi.knowledge.core.application.service.IKnowledgeConfigApplicationService;
import com.xspaceagi.system.application.service.SysDataPermissionApplicationService;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.tenant.thread.TenantRunnable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.google.common.collect.Lists;
import com.xspaceagi.knowledge.core.application.service.IKnowledgeDocumentApplicationService;
import com.xspaceagi.knowledge.core.application.service.IKnowledgeFullTextSyncService;
import com.xspaceagi.knowledge.core.spec.enums.KnowledgeTaskRunTypeEnum;
import com.xspaceagi.knowledge.core.spec.utils.ThreadTenantUtil;
import com.xspaceagi.knowledge.domain.dto.task.AutoRecordTask;
import com.xspaceagi.knowledge.domain.model.KnowledgeDocumentModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeQaSegmentRepository;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeRawSegmentRepository;
import com.xspaceagi.knowledge.domain.service.IKnowledgeConfigDomainService;
import com.xspaceagi.knowledge.domain.service.IKnowledgeDocumentDomainService;
import com.xspaceagi.knowledge.domain.service.IKnowledgeTaskArchiveAndRetryDomainService;
import com.xspaceagi.knowledge.domain.service.IKnowledgeTaskDomainService;
import com.xspaceagi.knowledge.domain.vectordb.VectorDBService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class KnowledgeDocumentApplicationService implements IKnowledgeDocumentApplicationService {

    @Resource
    private IKnowledgeDocumentDomainService knowledgeDocumentDomainService;

    @Resource
    private IKnowledgeConfigDomainService knowledgeConfigDomainService;

    @Resource
    private IKnowledgeTaskArchiveAndRetryDomainService knowledgeTaskArchiveAndRetryDomainService;

    @Resource
    private IKnowledgeTaskDomainService knowledgeTaskDomainService;

    @Resource
    private ThreadTenantUtil threadTenantUtil;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private IKnowledgeQaSegmentRepository knowledgeQaSegmentRepository;

    @Resource
    private IKnowledgeRawSegmentRepository knowledgeRawSegmentRepository;

    @Resource
    private VectorDBService vectorDBService;

    @Resource
    private IKnowledgeFullTextSyncService fullTextSyncService;

    @Lazy
    @Resource
    private IKnowledgeDocumentApplicationService self;

    //新增内容
    @Resource
    private SysDataPermissionApplicationService sysDataPermissionApplicationService;

    //新增内容
    @Resource
    private IKnowledgeConfigApplicationService knowledgeConfigApplicationService;

    @Override
    public KnowledgeDocumentModel queryOneInfoById(Long id) {
        return this.knowledgeDocumentDomainService.queryOneInfoById(id);
    }

    @Override
    public void deleteById(Long id, UserContext userContext) {
        var existObj = this.knowledgeDocumentDomainService.queryOneInfoById(id);
        if (Objects.isNull(existObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        // 校验用户和空间对应权限
        var spaceId = existObj.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        // 删除文档（包括数据库、向量库、全文检索，在 Domain 层事务内处理）
        this.knowledgeDocumentDomainService.deleteById(id, userContext);

        // 触发更新知识库文件预估大小值
        this.knowledgeDocumentDomainService.triggerUpdateKnowledgeFileSize(existObj.getKbId());
    }

    @Override
    public Long updateInfo(KnowledgeDocumentModel model, UserContext userContext) {

        var existObj = this.knowledgeDocumentDomainService.queryOneInfoById(model.getId());
        if (Objects.isNull(existObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        // 校验用户和空间对应权限
        var spaceId = existObj.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        var id = this.knowledgeDocumentDomainService.updateInfo(model, userContext);

        // 触发更新知识库文件预估大小值
        this.knowledgeDocumentDomainService.triggerUpdateKnowledgeFileSize(existObj.getKbId());
        return id;
    }

    @Override
    public Long updateDocName(Long docId, String name, UserContext userContext) {
        var existObj = this.knowledgeDocumentDomainService.queryOneInfoById(docId);
        if (Objects.isNull(existObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        // 校验用户和空间对应权限
        var spaceId = existObj.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        var updateObj = new KnowledgeDocumentModel();
        updateObj.setId(docId);
        updateObj.setName(name);
        updateObj.setKbId(existObj.getKbId());

        return this.knowledgeDocumentDomainService.updateDocName(updateObj, userContext);
    }

    @Override
    public Long customAddInfo(KnowledgeDocumentModel model, UserContext userContext) {
        // 查询基础配置,补全基础信息
        var knowledgeConfig = this.knowledgeConfigDomainService.queryOneInfoById(model.getKbId());
        if (Objects.isNull(knowledgeConfig)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        var spaceId = knowledgeConfig.getSpaceId();
        model.setSpaceId(spaceId);

        spacePermissionService.checkSpaceUserPermission(spaceId);

        var id = this.knowledgeDocumentDomainService.customAddInfo(model, userContext);
        // 触发更新知识库文件预估大小值
        this.knowledgeDocumentDomainService.triggerUpdateKnowledgeFileSize(model.getKbId());

        return id;
    }

    @Override
    public List<Long> batchAddInfo(List<KnowledgeDocumentModel> modelList, UserContext userContext) {
        var kbId = modelList.stream().map(KnowledgeDocumentModel::getKbId)
                .findFirst().orElse(null);
        if (Objects.isNull(kbId)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "请求参数");
        }

        // 查询基础配置,补全基础信息
        var knowledgeConfig = this.knowledgeConfigDomainService.queryOneInfoById(kbId);
        if (Objects.isNull(knowledgeConfig)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        var spaceId = knowledgeConfig.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        for (KnowledgeDocumentModel model : modelList) {
            model.setSpaceId(spaceId);
        }

        var ids = this.knowledgeDocumentDomainService.batchAddInfo(modelList, userContext);

        this.knowledgeDocumentDomainService.triggerUpdateKnowledgeFileSize(kbId);

        /*
        //新增权限内容
        //知识库大小验证
        UserDataPermissionDto userDataPermissions = sysDataPermissionApplicationService.getUserDataPermission(userContext.getUserId());
        System.out.println("=========userDataPermissions>>1");
        if(userDataPermissions != null && userDataPermissions.getKnowledgeStorageLimitGb() != null && userDataPermissions.getKnowledgeStorageLimitGb().doubleValue() != -1D ) {
            System.out.println("=========userDataPermissions>>2");
            //KnowledgeConfigModel model = knowledgeConfigRepository.queryOneInfoById(id);
            long id = kbId;
            var model = knowledgeConfigApplicationService.queryOneInfoById(id);
            if(model != null && model.getFileSize() != null) {
                System.out.println("=========userDataPermissions>>3");
                Long fileSize = model.getFileSize();
                Double gbSize = fileSize / (1024.0 * 1024 * 1024 );
                System.out.println("LimitGb："+userDataPermissions.getKnowledgeStorageLimitGb() + ",gbSize:" + gbSize);
                if(userDataPermissions.getKnowledgeStorageLimitGb().doubleValue() <= gbSize) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.knowledgeStorageUpperBound,
                            userDataPermissions.getKnowledgeStorageLimitGb());
                }
            }
        }
        //新增权限内容*/

        return ids;
    }

    @Override
    public void generateForQa(Long docId, UserContext userContext) {

        this.knowledgeDocumentDomainService.generateForQa(docId, userContext);
    }

    @Override
    public void generateEmbeddings(Long docId, UserContext userContext) {

        Runnable runnable = () -> this.knowledgeDocumentDomainService.generateEmbeddings(docId, userContext);
        TenantRunnable tenantRunnable = new TenantRunnable(runnable);

        threadTenantUtil.obtainCommonExecutor().execute(tenantRunnable);
    }

    @Override
    public void autoRetryTaskByDays(Integer days, UserContext userContext) {

        this.knowledgeTaskArchiveAndRetryDomainService.autoRunTask(days);
    }

    @Override
    public void retryAllTaskByDocId(Long docId, UserContext userContext) {
        // 文档分段,问答,向量化,全部走重试
        var existObj = this.knowledgeDocumentDomainService.queryOneInfoById(docId);
        if (Objects.isNull(existObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        // 校验用户和空间对应权限
        var spaceId = existObj.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        // 全部重试，需要删除文档对应的qa和raw分段,重新生成
        this.self.processDocumentRetry(docId, spaceId, existObj, userContext);

        // 重新生成
        this.knowledgeDocumentDomainService.workRunTaskForDocument(existObj, userContext, docId);
    }

    /**
     * 处理文档重试相关的操作
     * 
     * @param docId       文档ID
     * @param spaceId     空间ID
     * @param existObj    现有文档对象
     * @param userContext 用户上下文
     */
    @Override
    @DSTransactional(rollbackFor = Exception.class)
    public void processDocumentRetry(Long docId, Long spaceId, KnowledgeDocumentModel existObj,
            UserContext userContext) {
        // 删除文档对应的qa和raw分段
        this.knowledgeQaSegmentRepository.deleteByDocumentId(docId);
        this.knowledgeRawSegmentRepository.deleteByConfigDocumentId(docId);

        // 删除向量数据库的数据（语义检索）
        var kbId = existObj.getKbId();
        this.vectorDBService.removeDoc(kbId, docId);
        
        // 同步删除全文检索数据（在事务内）
        // 注意：重试时需要删除全文检索数据，但这里是在 Application 层，不要直接调用 Domain 层服务
        // 建议：这个方法应该移到 Domain 层，或者使用 Application 层的 fullTextSyncService
        try {
            fullTextSyncService.deleteDocumentFromQuickwit(docId, kbId, userContext);
            log.info("文档重试时删除全文检索数据成功: docId={}, kbId={}", docId, kbId);
        } catch (Exception e) {
            log.error("文档重试时删除全文检索数据失败: docId={}, kbId={}", docId, kbId, e);
            // 抛出异常，触发事务回滚
            throw KnowledgeException.build(BizExceptionCodeEnum.knowledgeDeleteDocFulltextFailed, e);
        }

        // 删除重试任务
        this.knowledgeTaskDomainService.deleteByDocIds(Lists.newArrayList(docId));

        // 记录新的重试任务
        var autoRecordTask = AutoRecordTask.builder()
                .docId(docId)
                .spaceId(spaceId)
                .kbId(kbId)
                .build();
        this.knowledgeTaskDomainService.createNewTask(autoRecordTask, KnowledgeTaskRunTypeEnum.SEGMENT, userContext);

    }

    @Override
    public List<KnowledgeDocumentModel> queryDocStatus(List<Long> docIds, UserContext userContext) {
        if (Objects.isNull(docIds) || docIds.isEmpty()) {
            return Lists.newArrayList();
        }
        var dataList = this.knowledgeDocumentDomainService.queryDocStatus(docIds);

        // 校验用户的文档权限,如果没有对应权限,则过滤掉
        var spaceIds = dataList.stream().map(KnowledgeDocumentModel::getSpaceId).distinct().toList();
        // 校验用户和空间对应权限
        var userId = userContext.getUserId();
        var spaceIdList = spacePermissionService.querySpaceIdList(userId);

        for (Long spaceId : spaceIds) {
            if (!spaceIdList.contains(spaceId)) {
                log.info("没有权限,过滤掉空间id:{},userId:{}", spaceId, userId);
                dataList.removeIf(doc -> doc.getSpaceId().equals(spaceId));
            }
        }

        return dataList;
    }

}
