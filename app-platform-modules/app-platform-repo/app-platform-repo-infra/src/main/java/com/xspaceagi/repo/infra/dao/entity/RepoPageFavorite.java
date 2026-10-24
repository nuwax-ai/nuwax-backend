package com.xspaceagi.repo.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

/**
 * 文档收藏实体（门户「我的收藏」）
 * @TableName repo_page_favorite
 */
@TableName(value = "repo_page_favorite")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RepoPageFavorite {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    private Long userId;

    private Long pageId;

    private Date created;

    private Date modified;

    private Integer yn;
}
