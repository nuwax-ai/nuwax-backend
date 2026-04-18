package com.xspaceagi.knowledge.sdk.sevice;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.knowledge.sdk.request.KnowledgeConfigRequestVo;
import com.xspaceagi.knowledge.sdk.request.KnowledgeCreateRequestVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeConfigResponseVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeConfigVo;
import com.xspaceagi.system.spec.common.UserContext;

import java.util.List;

/**
 * 知识库查询服务
 */
public interface IKnowledgeConfigRpcService {

    /**
     * 查询知识库列表,如果没有当前页和页大小,默认当前页为1,页大小为100
     *
     * @param knowledgeConfigRequestVo 请求参数
     * @return 知识库基础配置列表
     */
    public KnowledgeConfigResponseVo queryListKnowledgeConfig(KnowledgeConfigRequestVo knowledgeConfigRequestVo);

    /**
     * 创建知识库
     *
     * @param createRequestVo 创建知识库请求参数
     * @return 知识库主键id
     */
    Long createKnowledgeConfig(KnowledgeCreateRequestVo createRequestVo);

    /**
     * 根据知识库主键id查询知识库配置
     *
     * @param id 知识库主键id
     * @return 知识库配置
     */
    KnowledgeConfigVo queryKnowledgeConfigById(Long id);

    /**
     * 统计知识库总数
     *
     * @return 知识库总数
     */
    Long countTotalKnowledge(Long userId);

    /**
     * 管理端查询知识库列表
     *
     * @param pageNo 页码
     * @param pageSize 每页大小
     * @param name 名称模糊搜索
     * @param creatorIds 创建人ID列表
     * @param spaceId 空间ID
     * @return 知识库配置列表
     */
    IPage<KnowledgeConfigVo> queryListForManage(Integer pageNo, Integer pageSize, String name,
                                                java.util.List<Long> creatorIds, Long spaceId, Integer accessControl);

    /**
     * 管理端删除知识库
     *
     * @param id 知识库ID
     */
    void deleteForManage(Long id);

    /**
     * 根据ID列表查询知识库列表
     *
     * @param ids 知识库ID列表
     * @return 知识库配置列表
     */
    List<KnowledgeConfigVo> listByIds(java.util.List<Long> ids);

    /**
     * 管理端更新知识库管控状态
     *
     * @param id 知识库ID
     * @param status 管控状态
     */
    void updateAccessControlStatus(Long id, Integer status, UserContext userContext);

}
