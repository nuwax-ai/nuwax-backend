package com.xspaceagi.repo.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 评论线程视图对象：顶层评论 + 一层回复
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoCommentThreadVO {

    private RepoCommentVO comment;

    @Builder.Default
    private List<RepoCommentVO> replies = new ArrayList<>();
}
