package com.xspaceagi.repo.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文档版本分页列表视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPageVersionListVO {

    private long total;

    private List<RepoPageVersionVO> items;
}
