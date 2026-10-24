package com.xspaceagi.knowledge.core.application.service.impl;

import com.xspaceagi.knowledge.core.application.service.IKnowledgeConfigApplicationService;
import com.xspaceagi.knowledge.core.application.service.IKnowledgeFullTextSyncService;
import com.xspaceagi.knowledge.core.application.service.IKnowledgeQaSegmentApplicationService;
import com.xspaceagi.knowledge.core.application.vo.KnowledgeConfigApplicationRequestVo;
import com.xspaceagi.knowledge.core.spec.utils.ThreadTenantUtil;
import com.xspaceagi.knowledge.domain.model.KnowledgeConfigModel;
import com.xspaceagi.knowledge.domain.model.KnowledgeQaSegmentModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeConfigRepository;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeQaSegmentRepository;
import com.xspaceagi.knowledge.domain.service.IKnowledgeConfigDomainService;
import com.xspaceagi.knowledge.domain.service.IKnowledgeTaskArchiveAndRetryDomainService;
import com.xspaceagi.knowledge.domain.service.impl.KnowledgeQaSegmentDomainService;
import com.xspaceagi.knowledge.domain.vectordb.VectorDBService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import com.xspaceagi.system.spec.page.PageQueryParamVo;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.system.infra.service.QueryVoListDelegateService;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.tenant.thread.TenantRunnable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class KnowledgeConfigApplicationService implements IKnowledgeConfigApplicationService {

    @Resource
    private IKnowledgeConfigDomainService knowledgeConfigDomainService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private IKnowledgeConfigRepository knowledgeConfigRepository;
    @Resource
    private QueryVoListDelegateService queryVoListDelegateService;

    @Resource
    private IKnowledgeFullTextSyncService fullTextSyncService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private KnowledgeQaSegmentDomainService knowledgeQaSegmentDomainService;

    @Resource
    private ThreadTenantUtil threadTenantUtil;

    @Resource
    private VectorDBService vectorDBService;

    @Resource
    private IKnowledgeQaSegmentRepository knowledgeQaSegmentRepository;

    @Resource
    private IKnowledgeTaskArchiveAndRetryDomainService knowledgeTaskArchiveAndRetryDomainService;

    @Resource
    private IKnowledgeQaSegmentApplicationService knowledgeQaSegmentApplicationService;

    @Override
    public SuperPage<KnowledgeConfigModel> querySearchConfigs(
            PageQueryVo<KnowledgeConfigApplicationRequestVo> pageQueryVo) {

        var filter = pageQueryVo.getQueryFilter();
        pageQueryVo.setQueryFilter(filter);

        PageQueryParamVo pageQueryParamVo = new PageQueryParamVo(pageQueryVo);

        SuperPage<KnowledgeConfigModel> superPage = this.queryVoListDelegateService.queryVoList(
                this.knowledgeConfigRepository,
                pageQueryParamVo, null);

        return superPage;
    }

    @Override
    public KnowledgeConfigModel queryOneInfoById(Long id) {
        return this.knowledgeConfigDomainService.queryOneInfoById(id);
    }

    @Override
    public void deleteById(Long id, UserContext userContext) {
        var existObj = this.knowledgeConfigDomainService.queryOneInfoById(id);
        if (Objects.isNull(existObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        // 校验用户和空间对应权限
        var spaceId = existObj.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        // 删除知识库（包括数据库、向量库、全文检索，在 Domain 层事务内处理）
        this.knowledgeConfigDomainService.deleteById(id, userContext);
    }

    @Override
    public Long updateInfo(KnowledgeConfigModel model, UserContext userContext) {

        var existObj = this.knowledgeConfigDomainService.queryOneInfoById(model.getId());
        if (Objects.isNull(existObj)) {
            throw KnowledgeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        // 校验用户和空间对应权限
        var spaceId = existObj.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        //新增的内容
        //System.out.println("updateInfo========start=1>");
        if(existObj.getEmbeddingModelId() != model.getEmbeddingModelId()) {
            //当模型发生变化的时候会更新生成问题的方法，并根据问题进行向量化
            //System.out.println("updateInfo========start=2>");
            //批量更新生成问答的数据状态
            String updateSql = " update knowledge_qa_segment set has_embedding = ?, created = now() where kb_id = ? ";
            jdbcTemplate.update(updateSql, new Object[]{0, model.getId()});

            //主动推一次生成问题的行为
            String querysql = " select id from knowledge_qa_segment where kb_id = ? ";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(querysql, new Object[] {model.getId()}) ;
            List<Long> ids = new ArrayList<>();
            if ( list != null && list.size() > 0) {
                for (Map<String, Object> map : list) {
                    ids.add((Long) map.get("id"));
                }
            }
            //先移除掉向量化的数据
            vectorDBService.deleteCollection(model.getId());
            //vectorDBService.removeQa(ids, model.getId());
            if(ids != null && ids.size() > 0) {
                List<KnowledgeQaSegmentModel> modelList = knowledgeQaSegmentRepository.queryListByIds(ids);
                // 批量对新增的问答,进行向量化
                var runnable = new TenantRunnable(() -> {
                    knowledgeQaSegmentDomainService.batchAddEmbeddingQa(modelList, userContext);
                });
                threadTenantUtil.obtainOtherScheduledExecutor().execute(runnable);
            } else {
                List<KnowledgeQaSegmentModel> modelList = new ArrayList<>();
                // 批量对新增的问答,进行向量化
                var runnable = new TenantRunnable(() -> {
                    knowledgeQaSegmentDomainService.batchAddEmbeddingQa(modelList, userContext);
                });
                threadTenantUtil.obtainOtherScheduledExecutor().execute(runnable);
            }

        }
        //System.out.println("updateInfo========start=3>");

        return this.knowledgeConfigDomainService.updateInfo(model, userContext);
    }

    @Override
    public Long addInfo(KnowledgeConfigModel model, UserContext userContext) {
        var spaceId = model.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        return this.knowledgeConfigDomainService.addInfo(model, userContext);
    }

    @Override
    public List<KnowledgeConfigModel> queryListBySpaceId(Long spaceId) {
        return knowledgeConfigDomainService.queryListBySpaceId(spaceId);
    }

    @Override
    public Long queryTotalFileSize(Long kbId) {
        return this.knowledgeConfigDomainService.queryTotalFileSize(kbId);
    }

    @Override
    public void rebuildVectorDb() {
        log.info("全量重建向量库开始");

        // 1. 跨租户查询所有知识库,逐个补建 collection(已存在则跳过,initAndCheckCollection 幂等)
        List<Long> kbIds = TenantFunctions.callWithIgnoreCheck(() ->
                knowledgeConfigRepository.queryAllKbIds());
        int total = kbIds.size();
        log.info("全量重建向量库,知识库总数={}", total);
        int successCount = 0;
        int failCount = 0;
        int index = 0;
        for (Long kbId : kbIds) {
            index++;
            // knowledge_config 带 _tenant_id,补建时需忽略租户隔离,才能查到所有租户的知识库配置与模型维度
            final int current = index;
            try {
                TenantFunctions.runWithIgnoreCheck(() -> {
                    var model = knowledgeConfigDomainService.queryOneInfoById(kbId);
                    Long embeddingModelId = (model != null) ? model.getEmbeddingModelId() : null;
                    vectorDBService.deleteCollection(kbId);
                    vectorDBService.initAndCheckCollection(kbId, embeddingModelId);
                    log.info("补建向量库collection成功, 进度={}/{}, kbId={}, embeddingModelId={}",
                            current, total, kbId, embeddingModelId);
                });
                successCount++;
            } catch (Exception e) {
                failCount++;
                log.error("补建向量库collection失败, 进度={}/{}, kbId={}", current, total, kbId, e);
            }
        }
        log.info("补建向量库collection完成, 总数={}, 成功={}, 失败={}", total, successCount, failCount);

        // 2. 全表重置问答向量化状态(跨所有租户):has_embedding=0、created=当天0点,
        //    使其重新进入定时任务的向量化查询窗口(has_embedding=0 且 created 在最近N天内)
        LocalDateTime todayZero = LocalDate.now().atStartOfDay();
        int affected = knowledgeQaSegmentDomainService.resetAllForVectorRebuild(todayZero);
        log.info("重置问答向量化状态完成, 影响行数={}, todayZero={}", affected, todayZero);

        // 3. 异步触发一轮重新向量化(覆盖手动QA与文档QA两条通路),现有 5/10min 定时任务并行兜底
        var runnable = new TenantRunnable(() -> {
            log.info("异步全量重新向量化开始");
            try {
                // 手动添加的问答(doc_id=0):autoQaRunTask 内部已跨租户并按租户分组处理
                knowledgeTaskArchiveAndRetryDomainService.autoQaRunTask(7);

                // 文档解析的问答(doc_id!=0):queryListForEmbeddingQaAndEmbeddings 内部已跨租户并向量化。
                // 固定查第1页循环:每轮向量化后 has_embedding 置1,下一轮第1页取到新的待向量化数据,
                // 避免在动态变化的数据上递增页码导致漏取。
                int pageSize = 500;
                int round = 0;
                Long lastFirstId = null;
                int noProgressCount = 0;
                while (true) {
                    var list = knowledgeQaSegmentApplicationService.queryListForEmbeddingQaAndEmbeddings(
                            7, pageSize, 1);
                    if (list == null || list.isEmpty()) {
                        break;
                    }
                    round++;
                    // 防死循环:若连续多轮首条id不变,说明这些问答持续向量化失败(has_embedding未置1),
                    // 停止本轮交由定时任务兜底,避免无限重试
                    Long firstId = list.get(0).getId();
                    if (firstId.equals(lastFirstId)) {
                        noProgressCount++;
                        if (noProgressCount >= 3) {
                            log.warn("文档问答向量化连续{}轮无进展(存在持续失败的问答),停止本轮,剩余交由定时任务兜底, firstId={}",
                                    noProgressCount, firstId);
                            break;
                        }
                    } else {
                        noProgressCount = 0;
                        lastFirstId = firstId;
                    }
                    log.info("文档问答向量化进行中, 第{}轮, 本轮条数={}, firstId={}", round, list.size(), firstId);
                }
                log.info("异步全量重新向量化完成, 文档问答共处理{}轮", round);
            } catch (Exception e) {
                log.error("异步全量重新向量化异常", e);
            }
        });
        threadTenantUtil.obtainOtherScheduledExecutor().execute(runnable);

        log.info("全量重建向量库任务已提交后台执行,知识库总数={}, 补建成功={}, 失败={}, 重置行数={}",
                total, successCount, failCount, affected);
    }

}
