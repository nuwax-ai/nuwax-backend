package com.xspaceagi.knowledge.core.application.service;

import com.xspaceagi.knowledge.core.application.vo.KnowledgeConfigApplicationRequestVo;
import com.xspaceagi.knowledge.domain.model.KnowledgeConfigModel;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;

import java.util.List;

public interface IKnowledgeConfigApplicationService {


    /**
     * 给api提供rpc查询功能,搜索知识库基础配置
     *
     * @param pageQueryVo 请求搜索参数
     * @return
     */
    SuperPage<KnowledgeConfigModel> querySearchConfigs(PageQueryVo<KnowledgeConfigApplicationRequestVo> pageQueryVo);


    /**
     * 模板配置详情
     *
     * @param id 模板id
     */
    KnowledgeConfigModel queryOneInfoById(Long id);

    /**
     * 删除根据主键id
     *
     * @param id id
     * @param userContext 用户上下文
     */
    void deleteById(Long id, UserContext userContext);


    /**
     * 更新
     *
     * @param model 模型
     * @return id
     */
    Long updateInfo(KnowledgeConfigModel model, UserContext userContext);

    /**
     * 新增
     */
    Long addInfo(KnowledgeConfigModel model, UserContext userContext);


    /**
     * 根据空间ID查询知识库列表
     *
     * @param spaceId
     * @return
     */
    List<KnowledgeConfigModel> queryListBySpaceId(Long spaceId);

    /**
     * 查询知识库的文件大小
     * 
     * @param kbId 知识库id
     * @return 文件大小
     */
    Long queryTotalFileSize(Long kbId);

    /**
     * 全量重建向量库(用于迁移到全新的空 Milvus):
     * 跨租户补建所有知识库的 collection → 全表重置问答向量化状态(has_embedding=0、created=当天0点)
     * → 异步触发一轮重新向量化(手动QA+文档QA),现有定时任务并行兜底。
     * <p>
     * 注意:属一次性迁移操作,重复调用会导致已重灌的数据被重复向量化。
     */
    void rebuildVectorDb();
}