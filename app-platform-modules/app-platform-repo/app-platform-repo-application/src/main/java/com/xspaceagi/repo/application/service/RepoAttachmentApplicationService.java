package com.xspaceagi.repo.application.service;

import com.xspaceagi.repo.domain.model.RepoAttachmentDomain;

import java.util.List;

/**
 * 文档附件应用服务（映射挂接，文件本体在 file 模块 FileRecord）。
 * <p>
 * 挂接/列表要求空间成员；软删限上传人或空间管理员/属主。
 */
public interface RepoAttachmentApplicationService {

    /**
     * 挂接页面级附件
     *
     * @param pageId   页面ID
     * @param fileKey  file 模块 FileRecord.fileKey
     * @param fileName 文件名
     * @param mime     MIME类型
     * @param size     文件大小（字节）
     * @return 附件领域模型
     */
    RepoAttachmentDomain addPageAttachment(Long pageId, String fileKey, String fileName, String mime, Long size);

    /**
     * 挂接空间级附件
     *
     * @param spaceId  空间ID
     * @param fileKey  file 模块 FileRecord.fileKey
     * @param fileName 文件名
     * @param mime     MIME类型
     * @param size     文件大小（字节）
     * @return 附件领域模型
     */
    RepoAttachmentDomain addSpaceAttachment(Long spaceId, String fileKey, String fileName, String mime, Long size);

    /**
     * 查询页面附件列表
     *
     * @param pageId 页面ID
     * @return 附件列表（id 倒序）
     */
    List<RepoAttachmentDomain> listPageAttachments(Long pageId);

    /**
     * 查询空间全部附件（含页面级与空间级）
     *
     * @param spaceId 空间ID
     * @return 附件列表（id 倒序）
     */
    List<RepoAttachmentDomain> listSpaceAttachments(Long spaceId);

    /**
     * 软删除附件（限上传人或空间管理员/属主）
     *
     * @param attachmentId 附件ID
     */
    void deleteAttachment(Long attachmentId);
}
