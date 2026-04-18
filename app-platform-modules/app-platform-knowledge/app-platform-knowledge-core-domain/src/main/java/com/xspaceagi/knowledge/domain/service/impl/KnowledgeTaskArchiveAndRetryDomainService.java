package com.xspaceagi.knowledge.domain.service.impl;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.xspaceagi.knowledge.core.spec.enums.KnowledgeTaskRunTypeEnum;
import com.xspaceagi.knowledge.domain.model.KnowledgeQaSegmentModel;
import com.xspaceagi.knowledge.domain.model.KnowledgeTaskModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeTaskHistoryRepository;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeTaskRepository;
import com.xspaceagi.knowledge.domain.service.IKnowledgeDocumentDomainService;
import com.xspaceagi.knowledge.domain.service.IKnowledgeQaSegmentDomainService;
import com.xspaceagi.knowledge.domain.service.IKnowledgeTaskArchiveAndRetryDomainService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jboss.logging.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KnowledgeTaskArchiveAndRetryDomainService implements IKnowledgeTaskArchiveAndRetryDomainService {

    @Resource
    private IKnowledgeTaskHistoryRepository knowledgeTaskHistoryRepository;

    @Resource
    private IKnowledgeTaskRepository knowledgeTaskRepository;

    @Resource
    private IKnowledgeDocumentDomainService knowledgeDocumentDomainService;

    @Resource
    private IKnowledgeQaSegmentDomainService knowledgeQaSegmentDomainService;

    @Resource
    private UserApplicationService userApplicationService;

    @Override
    public void autoRunTask(Integer days) {
        log.info("Auto retry start, last [{}] days", days);

        var data = this.knowledgeTaskRepository.queryListForRetryByDays(days);
        // 生成默认用户信息
        UserContext userContext = UserContext.builder()
                .userId(0L)
                .userName("系统重试")
                .build();
        for (var item : data) {
            Integer reqId = ThreadLocalRandom.current().nextInt(100, 999999);
            MDC.put("tid", reqId + "" + Instant.now().toEpochMilli());

            var docId = item.getDocId();

            try {
                // 设置租户id
                var tenantId = item.getTenantId();
                RequestContext.setThreadTenantId(tenantId);

                var taskType = item.getType();
                KnowledgeTaskRunTypeEnum runTypeEnum = KnowledgeTaskRunTypeEnum.getByType(taskType);

                var docModel = this.knowledgeDocumentDomainService.queryOneInfoById(docId);

                if (Objects.isNull(docModel)) {
                    log.error("Cannot retry, doc missing, docId [{}]", docId);
                    continue;
                }
                //工作流调用时需要设置用户信息
                UserDto user = userApplicationService.queryById(docModel.getCreatorId());
                RequestContext.get().setUser(user);
                userContext.setUserId(user.getId());
                userContext.setUserId(user.getId());
                userContext.setUserName(user.getNickName() != null ? user.getNickName() : user.getUserName());
                userContext.setNickName(user.getNickName());
                userContext.setAvatar(user.getAvatar());
                userContext.setEmail(user.getEmail());
                userContext.setPhone(user.getPhone());
                userContext.setTenantId(user.getTenantId());
                this.knowledgeDocumentDomainService.workRetryRunTaskForDocument(runTypeEnum, docModel, userContext,
                        docId);

                log.info("Retry OK, docId [{}]", docId);

            } catch (Exception e) {
                log.error("Auto retry failed, docId={}", docId, e);
            } finally {
                // 重试次数+1
                this.knowledgeTaskRepository.incrementRetryCount(item.getId(), userContext);

                RequestContext.remove();
                MDC.clear();
            }

        }

        log.info("Retry all done, last [{}] days", days);

    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public void archiveTask(Integer days) {
        log.info("Auto archive start, older than [{}] days", days);
        var data = this.knowledgeTaskRepository.queryListForArchiveByDaysAndSuccess(days);

        // 根据租户id(tenantId)进行分组,不然租户隔离会报错

        var groupByTenantIdData = data.stream()
                .collect(Collectors.groupingBy(KnowledgeTaskModel::getTenantId));

        // 生成默认用户信息
        UserContext userContext = UserContext.builder()
                .userId(0L)
                .userName("系统归档")
                .build();
        for (var item : groupByTenantIdData.entrySet()) {
            Integer reqId = ThreadLocalRandom.current().nextInt(100, 999999);
            MDC.put("tid", reqId + "" + Instant.now().toEpochMilli());

            var ids = item.getValue().stream()
                    .map(KnowledgeTaskModel::getDocId)
                    .toList();

            try {
                // 租户id
                var tenantId = item.getKey();
                RequestContext.setThreadTenantId(tenantId);

                this.knowledgeTaskHistoryRepository.batchArchiveInfo(data, userContext);

            } catch (Exception e) {
                log.error("Archive failed, docId [{}]", ids);
            } finally {
                RequestContext.remove();
                MDC.clear();
            }
        }

    }

    @Override
    public void autoQaRunTask(Integer days) {
        log.info("Auto QA paging embed, older than [{}] days", days);

        // 定义页大小,分页查询待向量化的问答,进行重试
        Integer pageSize = 300;
        Integer pageNum = 1;

        // 生成默认用户信息
        UserContext userContext = UserContext.builder()
                .userId(0L)
                .userName("系统重试")
                .build();

        // 查询最近几天新增的问答
        do {
            final var finalPageNum = pageNum;
            //查询手动添加的问答,待向量化的问答,进行向量化;其他的问答,会通过 autoGenerateQaEmbeddings 任务重试,这里不管
            var data = TenantFunctions.callWithIgnoreCheck(() -> {
                return this.knowledgeQaSegmentDomainService.queryListForEmbeddingQaAndEmbeddingsAndRawIdIsNull(days, pageSize,
                        finalPageNum);
            });
                // 如果数据为空,则退出
            if (data.isEmpty()) {
                log.info("Auto QA paging done, no data");
                break;
            }
            pageNum++;

            // 根据租户id(tenantId)进行分组,不然租户隔离会报错

            var groupByTenantIdData = data.stream()
                    .collect(Collectors.groupingBy(KnowledgeQaSegmentModel::getTenantId));

            for (var item : groupByTenantIdData.entrySet()) {

                var qaList = item.getValue();
                var tenantId = item.getKey();
                var ids = qaList.stream()
                        .map(KnowledgeQaSegmentModel::getId)
                        .toList();

                log.info("Auto QA paging start, docId [{}]", ids);
                try {
                    Integer reqId = ThreadLocalRandom.current().nextInt(100, 999999);
                    MDC.put("tid", reqId + "" + Instant.now().toEpochMilli());

                    RequestContext.setThreadTenantId(tenantId);

                    // 批量向量化
                    this.knowledgeQaSegmentDomainService.batchAddEmbeddingQa(qaList, userContext);
                    log.info("Auto QA paging OK, docId [{}]", ids);
                } catch (Exception e) {
                    log.error("Auto QA failed, docId [{}]", ids, e);
                } finally {
                    RequestContext.remove();
                    MDC.clear();
                }

            }
        } while (true);

    }
}
