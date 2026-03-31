package com.xspaceagi.knowledge.core.application.service.impl;

import com.xspaceagi.knowledge.core.application.service.IKnowledgeConfigApplicationService;
import com.xspaceagi.knowledge.core.application.service.IKnowledgeFullTextSyncService;
import com.xspaceagi.knowledge.core.application.vo.KnowledgeConfigApplicationRequestVo;
import com.xspaceagi.knowledge.core.spec.utils.ThreadTenantUtil;
import com.xspaceagi.knowledge.domain.model.KnowledgeConfigModel;
import com.xspaceagi.knowledge.domain.model.KnowledgeQaSegmentModel;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeConfigRepository;
import com.xspaceagi.knowledge.domain.repository.IKnowledgeQaSegmentRepository;
import com.xspaceagi.knowledge.domain.service.IKnowledgeConfigDomainService;
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
import com.xspaceagi.system.spec.tenant.thread.TenantRunnable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
            throw KnowledgeException.build(BizExceptionCodeEnum.KNOWLEDGE_ERROR_5001);
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
            throw KnowledgeException.build(BizExceptionCodeEnum.KNOWLEDGE_ERROR_5001);
        }
        // 校验用户和空间对应权限
        var spaceId = existObj.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        //新增的内容
        System.out.println("updateInfo========start=1>");
        if(existObj.getEmbeddingModelId() != model.getEmbeddingModelId()) {
            //当模型发生变化的时候会更新生成问题的方法，并根据问题进行向量化
            System.out.println("updateInfo========start=2>");
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
        System.out.println("updateInfo========start=3>");

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

}
