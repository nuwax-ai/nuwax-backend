package com.xspaceagi.repo.application.service.dto;

import com.xspaceagi.repo.domain.model.RepoShareDomain;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档空间分享记录视图：在领域对象基础上补目标空间的展示名与类型。
 * <p>
 * 为什么要补类型：授权管理需要把「空间（Team）」与「群组（Group）」分开展示，
 * 而前端 {@code /api/space/list} 只下发 Personal / Team，群组空间根本不在列表里，
 * 无法由前端自行解析，只能由后端随分享记录一并发回。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoShareSpaceVO {

    private Long id;

    private Long pageId;

    /**
     * 目标空间ID（该空间全部成员获得 permBits 权限）
     */
    private Long targetSpaceId;

    /**
     * 目标空间名称；空间已被删除时为空
     */
    private String targetSpaceName;

    /**
     * 目标空间类型：Personal / Team / Class / Group / DM；空间已被删除时为空
     */
    private String targetSpaceType;

    private Integer permBits;

    private Long createdBy;

    private Date created;

    public static RepoShareSpaceVO from(RepoShareDomain domain, String spaceName, String spaceType) {
        return RepoShareSpaceVO.builder()
                .id(domain.getId())
                .pageId(domain.getPageId())
                .targetSpaceId(domain.getTargetSpaceId())
                .targetSpaceName(spaceName)
                .targetSpaceType(spaceType)
                .permBits(domain.getPermBits())
                .createdBy(domain.getCreatedBy())
                .created(domain.getCreated())
                .build();
    }
}