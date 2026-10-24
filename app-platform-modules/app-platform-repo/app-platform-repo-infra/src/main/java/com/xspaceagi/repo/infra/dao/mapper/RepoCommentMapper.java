package com.xspaceagi.repo.infra.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.repo.infra.dao.entity.RepoComment;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文档评论Mapper
 *
 * @description 针对表【repo_comment(文档评论表)】的数据库操作Mapper
 * @Entity com.xspaceagi.repo.infra.dao.entity.RepoComment
 */
public interface RepoCommentMapper extends BaseMapper<RepoComment> {

    /**
     * 查询页面全部有效评论（按 id 升序）。
     * 租户条件由平台 TenantLineInnerInterceptor 自动注入。
     *
     * @param pageId 页面ID
     * @return 评论列表
     */
    List<RepoComment> listByPageId(@Param("pageId") Long pageId);

    /**
     * 更新解决状态。
     *
     * @param id       评论ID
     * @param resolved 0未解决 1已解决
     * @return 受影响行数
     */
    int updateResolved(@Param("id") Long id, @Param("resolved") int resolved);

    /**
     * 批量软删除评论（置 yn=0）。
     *
     * @param ids 评论ID集合
     * @return 受影响行数
     */
    int softDeleteByIds(@Param("ids") List<Long> ids);
}
