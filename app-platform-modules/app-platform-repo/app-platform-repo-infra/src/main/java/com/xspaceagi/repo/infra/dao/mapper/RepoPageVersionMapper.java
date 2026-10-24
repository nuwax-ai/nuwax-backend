package com.xspaceagi.repo.infra.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.repo.infra.dao.entity.RepoPageVersion;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文档版本快照Mapper
 *
 * @description 针对表【repo_page_version(文档版本快照表)】的数据库操作Mapper
 * @Entity com.xspaceagi.repo.infra.dao.entity.RepoPageVersion
 */
public interface RepoPageVersionMapper extends BaseMapper<RepoPageVersion> {

    /**
     * 分页查询页面版本列表（按 id 倒序，不含 ydoc_snapshot/html 大字段）。
     * 租户条件由平台 TenantLineInnerInterceptor 自动注入。
     *
     * @param pageId 页面ID
     * @param offset 偏移量
     * @param size   分页大小
     * @return 版本列表（不含大字段）
     */
    List<RepoPageVersion> listByPageId(@Param("pageId") Long pageId,
                                       @Param("offset") int offset,
                                       @Param("size") int size);

    /**
     * 统计页面版本总数。
     *
     * @param pageId 页面ID
     * @return 版本数量
     */
    long countByPageId(@Param("pageId") Long pageId);

    /**
     * 按主键查询版本（含 ydoc_snapshot/html 大字段）。
     *
     * @param id 版本ID
     * @return 版本实体
     */
    RepoPageVersion selectFullById(@Param("id") Long id);
}
