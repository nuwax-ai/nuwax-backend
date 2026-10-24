package com.xspaceagi.repo.application.service.dto;

import com.xspaceagi.repo.domain.model.RepoPageVersionDomain;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 版本分页查询结果（items 不含 ydocSnapshot/html 大字段）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPageVersionListResult {

    /**
     * 版本总数
     */
    private long total;

    /**
     * 当前页版本列表（按 id 倒序）
     */
    private List<RepoPageVersionDomain> items;
}
