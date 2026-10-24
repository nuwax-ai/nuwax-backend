package com.xspaceagi.knowledge.core.infra.knowledgegraph.service.impl;

import com.xspaceagi.knowledge.core.infra.knowledgegraph.docparser.SurrealDbService;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.service.IGraphTripleSyncTaskService;
import com.xspaceagi.knowledge.core.infra.knowledgegraph.service.IKnowledgeGraphDomainService;
import com.xspaceagi.knowledge.domain.model.DocumentParseStatusModel;
import com.xspaceagi.knowledge.domain.model.DocumentParseStatusRecordModel;
import com.xspaceagi.knowledge.domain.repository.IDocumentParseStatusRecordRepository;
import com.xspaceagi.knowledge.domain.repository.IDocumentParseStatusRepository;
import com.xspaceagi.system.spec.common.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class GraphTripleSyncTaskService implements IGraphTripleSyncTaskService {

    @Resource
    private IKnowledgeGraphDomainService knowledgeGraphDomainService;

    @Resource
    private IDocumentParseStatusRepository documentParseStatusRepository;

    @Resource
    private IDocumentParseStatusRecordRepository documentParseStatusRecordRepository;

    @Resource
    private SurrealDbService surrealDbService;

    /*
    @Override
    public void syncTaskGraphTriple() {

        UserContext userContext = UserContext.builder()
                .userId(0L)
                .userName("知识图谱生成")
                .build();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String currentTime = LocalDateTime.now().format(formatter);

        // 创建执行记录
        DocumentParseStatusRecordModel recordModel = new DocumentParseStatusRecordModel();
        recordModel.setCreateTime(currentTime);
        recordModel.setExecuteState("0");
        recordModel.setStartTime(currentTime);
        recordModel.setSuccessCount("0");
        recordModel.setErrorCount("0");

        Long recordId = documentParseStatusRecordRepository.addInfo(recordModel, userContext);
        recordModel.setId(recordId.intValue());

        try {
            // 查询所有triple_status为1的数据
            List<DocumentParseStatusModel> statusList = documentParseStatusRepository.queryListByTripleStatus(1);

            if (statusList == null || statusList.isEmpty()) {
                log.info("没有需要同步的三元组数据");
                updateRecordSuccess(recordModel, 0, 0, "无需要同步的数据", null);
                return;
            }

            int successCount = 0;
            int errorCount = 0;
            StringBuilder executeContent = new StringBuilder();

            for (DocumentParseStatusModel status : statusList) {
                Long documentId = status.getDocumentId();

                try {
                    // 1. 删除 SurrealDB 中该文档的所有三元组
                    boolean surrealDbSuccess = surrealDbService.deleteTriplesByDocumentId(documentId);
                    log.info("删除 SurrealDB 三元组: documentId={}, success={}", documentId, surrealDbSuccess);
                } catch (Exception e) {

                }

                try {
                    // 调用异步生成知识图谱方法
                    knowledgeGraphDomainService.generateKnowledgeGraph(documentId, userContext);
                    successCount++;
                    executeContent.append("文档ID: ").append(documentId).append(" - 成功; ");
                    log.info("文档ID: {} 三元组同步成功", documentId);
                } catch (Exception e) {
                    errorCount++;
                    executeContent.append("文档ID: ").append(documentId).append(" - 失败: ").append(e.getMessage()).append("; ");
                    log.error("文档ID: {} 三元组同步失败: {}", documentId, e.getMessage(), e);
                }
            }

            // 更新记录为成功状态
            updateRecordSuccess(recordModel, successCount, errorCount, executeContent.toString(), null);

        } catch (Exception e) {
            log.error("同步三元组任务执行异常", e);
            // 更新记录为失败状态
            updateRecordError(recordModel, e.getMessage());
        }

        log.info("Syncing task graph triple end");
    }*/

    private void updateRecordSuccess(DocumentParseStatusRecordModel recordModel, int successCount, int errorCount, String executeContent, String executeErrorLogs) {
        LocalDateTime endTime = LocalDateTime.now();

        recordModel.setExecuteState("1");
        recordModel.setSuccessCount((long) successCount);
        recordModel.setErrorCount((long) errorCount);
        recordModel.setEndTime(endTime);
        recordModel.setExecuteContent(executeContent);
        if (executeErrorLogs != null) {
            recordModel.setExecuteErrorLogs(executeErrorLogs);
        }

        documentParseStatusRecordRepository.updateInfo(recordModel, null);
    }

    private void updateRecordError(DocumentParseStatusRecordModel recordModel, String errorMessage) {
        LocalDateTime endTime = LocalDateTime.now();

        recordModel.setExecuteState("2");
        recordModel.setEndTime(endTime);
        recordModel.setExecuteErrorLogs(errorMessage);

        documentParseStatusRecordRepository.updateInfo(recordModel, null);
    }
}
