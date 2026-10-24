package com.xspaceagi.repo.application.service.dto;

import com.xspaceagi.repo.domain.model.RepoCommentDomain;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 评论线程：顶层评论 + 一层回复
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoCommentThread {

    /**
     * 顶层评论
     */
    private RepoCommentDomain comment;

    /**
     * 回复列表（按 id 升序）
     */
    @Builder.Default
    private List<RepoCommentDomain> replies = new ArrayList<>();
}
