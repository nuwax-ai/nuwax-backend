package com.xspaceagi.repo.web.dto;

import lombok.Data;

/**
 * 新建分享链接请求（需求4：链接只读）
 */
@Data
public class RepoShareLinkCreateRequest {

    /**
     * 访问方式：login=登录可访问 public=任何人可访问（含匿名）
     */
    private String accessType;

    /**
     * 是否允许评论（需求5）：1=允许 0=不允许；缺省为允许
     */
    private Integer allowComment;

    /**
     * 链接授予权限位（本轮需求4：只读类 查看/评论/导出/复制，后端强制去编辑位；缺省仅查看）
     */
    private Integer permBits;
}
