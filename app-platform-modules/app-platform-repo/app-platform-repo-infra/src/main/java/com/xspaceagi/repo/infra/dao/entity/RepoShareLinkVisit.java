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
 * 分享链接访问日志实体（分享链接「统计」用）。
 * <p>
 * 与 repo_page_visit 的 upsert 语义不同，本表为 append-only：每次通过链接访问都插入一行，
 * 用于精确统计「访问次数」（PV）与「去重访问用户 + 最近访问时间」（UV）。
 * @TableName repo_share_link_visit
 */
@TableName(value = "repo_share_link_visit")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RepoShareLinkVisit {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(value = "_tenant_id")
    private Long tenantId;

    private Long linkId;

    private Long pageId;

    private Long userId;

    private Date visitedAt;

    private Date created;
}
