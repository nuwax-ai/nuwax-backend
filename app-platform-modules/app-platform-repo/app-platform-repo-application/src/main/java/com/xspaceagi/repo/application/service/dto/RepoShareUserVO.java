package com.xspaceagi.repo.application.service.dto;

import com.xspaceagi.repo.domain.model.RepoShareUserDomain;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档个人分享记录视图（需求3）：在领域对象基础上补被分享人展示名。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoShareUserVO {

    private Long id;

    private Long pageId;

    /**
     * 被分享人用户ID
     */
    private Long targetUserId;

    /**
     * 被分享人展示名（昵称优先，空则用户名）
     */
    private String targetUserName;

    /**
     * 权限位组合
     */
    private Integer permBits;

    /**
     * 分享发起人ID
     */
    private Long createdBy;

    private Date created;

    public static RepoShareUserVO from(RepoShareUserDomain domain, String targetUserName) {
        return RepoShareUserVO.builder()
                .id(domain.getId())
                .pageId(domain.getPageId())
                .targetUserId(domain.getTargetUserId())
                .targetUserName(targetUserName)
                .permBits(domain.getPermBits())
                .createdBy(domain.getCreatedBy())
                .created(domain.getCreated())
                .build();
    }
}
