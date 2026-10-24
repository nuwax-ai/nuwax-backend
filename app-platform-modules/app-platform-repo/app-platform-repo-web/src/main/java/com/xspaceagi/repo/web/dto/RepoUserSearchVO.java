package com.xspaceagi.repo.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户搜索精简视图（需求3：分享给个人选人用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoUserSearchVO {

    private Long id;

    private String userName;

    private String nickName;

    private String avatar;
}
