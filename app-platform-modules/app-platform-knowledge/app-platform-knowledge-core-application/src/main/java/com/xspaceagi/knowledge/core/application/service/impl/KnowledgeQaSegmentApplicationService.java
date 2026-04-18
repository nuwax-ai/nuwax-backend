package com.xspaceagi.knowledge.core.application.service.impl;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.xspaceagi.knowledge.core.application.service.IKnowledgeQaSegmentApplicationService;
import com.xspaceagi.knowledge.domain.dto.EmbeddingStatusDto;
import com.xspaceagi.knowledge.domain.dto.qa.QAQueryDto;
import com.xspaceagi.knowledge.domain.dto.qa.QAResDto;
import com.xspaceagi.knowledge.domain.model.KnowledgeQaSegmentModel;
import com.xspaceagi.knowledge.domain.model.excel.KnowledgeQaExcelModel;
import com.xspaceagi.knowledge.domain.service.IKnowledgeConfigDomainService;
import com.xspaceagi.knowledge.domain.service.IKnowledgeDocumentDomainService;
import com.xspaceagi.knowledge.domain.service.IKnowledgeQaSegmentDomainService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;

import cn.idev.excel.EasyExcel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class KnowledgeQaSegmentApplicationService implements IKnowledgeQaSegmentApplicationService {

    @Resource
    private IKnowledgeQaSegmentDomainService knowledgeQaSegmentDomainService;

    @Resource
    private IKnowledgeConfigDomainService knowledgeConfigDomainService;

    @Resource
    private IKnowledgeDocumentDomainService knowledgeDocumentDomainService;
    @Resource
    private SpacePermissionService spacePermissionService;

    @Override
    public KnowledgeQaSegmentModel queryOneInfoById(Long id) {
        return this.knowledgeQaSegmentDomainService.queryOneInfoById(id);
    }

    @Override
    public void deleteById(Long id) {

        var knowledgeQaSegment = this.knowledgeQaSegmentDomainService.queryOneInfoById(id);
        if (Objects.isNull(knowledgeQaSegment)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        var spaceId = knowledgeQaSegment.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        this.knowledgeQaSegmentDomainService.deleteById(id);
    }

    @Override
    public Long updateInfo(KnowledgeQaSegmentModel model, UserContext userContext) {
        var knowledgeQaSegment = this.knowledgeQaSegmentDomainService.queryOneInfoById(model.getId());
        if (Objects.isNull(knowledgeQaSegment)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        var spaceId = knowledgeQaSegment.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        return this.knowledgeQaSegmentDomainService.updateInfo(model, userContext);
    }

    @Override
    public Long addInfo(KnowledgeQaSegmentModel model, UserContext userContext) {

        // 查询基础配置,补全基础信息
        var knowledgeConfig = this.knowledgeConfigDomainService.queryOneInfoById(model.getKbId());
        if (Objects.isNull(knowledgeConfig)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        var spaceId = knowledgeConfig.getSpaceId();
        model.setSpaceId(spaceId);

        spacePermissionService.checkSpaceUserPermission(spaceId);

        return this.knowledgeQaSegmentDomainService.addInfo(model, userContext);
    }

    @Override
    public List<QAResDto> search(QAQueryDto qaQueryDto, boolean ignoreKBStatus) {
        // 根据kbId查询知识库配置,校验用户和空间对应权限
        var knowledgeConfig = this.knowledgeConfigDomainService.queryOneInfoById(qaQueryDto.getKbId());
        if (Objects.isNull(knowledgeConfig)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        var dataList = this.knowledgeQaSegmentDomainService.search(qaQueryDto, ignoreKBStatus);
        return dataList;
    }

    @Override
    public List<QAResDto> searchForWeb(QAQueryDto qaQueryDto, boolean ignoreKBStatus) {
        // 根据kbId查询知识库配置,校验用户和空间对应权限
        var knowledgeConfig = this.knowledgeConfigDomainService.queryOneInfoById(qaQueryDto.getKbId());
        if (Objects.isNull(knowledgeConfig)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        var spaceId = knowledgeConfig.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        var dataList = this.knowledgeQaSegmentDomainService.search(qaQueryDto, ignoreKBStatus);
        return dataList;
    }


    @Override
    public EmbeddingStatusDto queryEmbeddingStatus(Long docId) {
        return this.knowledgeQaSegmentDomainService.queryEmbeddingStatus(docId);
    }

    @Override
    public List<KnowledgeQaSegmentModel> queryListForEmbeddingQaAndEmbeddings(Integer days, Integer pageSize,
            Integer pageNum) {
        log.info("查询待向量化的问答列表,查询最近{}天,每页大小{},页码{}", days, pageSize, pageNum);
        var dataList = TenantFunctions.callWithIgnoreCheck(() -> {
            return this.knowledgeQaSegmentDomainService.queryListForEmbeddingQaAndEmbeddings(days, pageSize,
                    pageNum);

        });

        var size = dataList.size();
        log.info("查询待向量化的问答列表,查询最近{}天,每页大小{},页码{},共{}条", days, pageSize, pageNum, size);
        if (size > 0) {
            var userContext = UserContext.builder()
                    .userId(0L)
                    .userName("系统")
                    .build();
            // 安装租户id分组:tenantId
            Map<Long, List<KnowledgeQaSegmentModel>> tenantIdGroup = dataList.stream()
                    .collect(Collectors.groupingBy(KnowledgeQaSegmentModel::getTenantId));
            for (Map.Entry<Long, List<KnowledgeQaSegmentModel>> entry : tenantIdGroup.entrySet()) {
                Long tenantId = entry.getKey();
                List<KnowledgeQaSegmentModel> segmentList = entry.getValue();
                try {
                    // 设置租户id
                    RequestContext.setThreadTenantId(tenantId);

                    // 按照文档id来分组处理: docId
                    Map<Long, List<KnowledgeQaSegmentModel>> docIdGroup = segmentList.stream()
                            .collect(Collectors.groupingBy(KnowledgeQaSegmentModel::getDocId));
                    for (Map.Entry<Long, List<KnowledgeQaSegmentModel>> docEntry : docIdGroup.entrySet()) {
                        Long docId = docEntry.getKey();
                        List<KnowledgeQaSegmentModel> segmentListForDoc = docEntry.getValue();
                        if (log.isDebugEnabled()) {
                            log.debug("生成文档id={}的问答向量化,问答分段列表:{}", docId, segmentListForDoc);
                        }
                        // 捕捉异常,避免影响其他docId重试
                        try {
                            this.knowledgeDocumentDomainService.generateEmbeddingsByQaSegment(segmentListForDoc,
                                    userContext);
                        } catch (Exception e) {
                            log.error("生成文档id={}的问答向量化失败,问答分段列表:{}", docId, segmentListForDoc, e);
                        }
                    }

                } finally {
                    RequestContext.remove();
                }
            }
        }
        return dataList;
    }

    @Override
    public void importQaFromExcel(Long kbId, MultipartFile file, UserContext userContext) {
        // 校验知识库是否存在
        var knowledgeConfig = this.knowledgeConfigDomainService.queryOneInfoById(kbId);
        if (Objects.isNull(knowledgeConfig)) {
            log.warn("知识库不存在,知识库ID={}", kbId);
            throw KnowledgeException.build(BizExceptionCodeEnum.knowledgeKbNotFoundById, kbId);
        }

        var spaceId = knowledgeConfig.getSpaceId();

        // 校验空间权限
        spacePermissionService.checkSpaceUserPermission(spaceId);

        this.knowledgeQaSegmentDomainService.importQaFromExcel(kbId, file, userContext);
    }

    @Override
    public byte[] getExcelTemplate() {
        // 模板给个问答示例: "这是一个示例问题？", "这是一个示例答案。"
        var qaList = new ArrayList<KnowledgeQaExcelModel>();
        qaList.add(KnowledgeQaExcelModel.builder()
                .question("这是一个示例问题？")
                .answer("这是一个示例答案。")
                .build());

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        EasyExcel.write(outputStream, KnowledgeQaExcelModel.class)
                .sheet("问答模板")
                .doWrite(qaList);

        return outputStream.toByteArray();
    }
}
