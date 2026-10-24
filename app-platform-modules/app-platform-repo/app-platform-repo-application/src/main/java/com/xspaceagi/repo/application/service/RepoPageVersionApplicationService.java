package com.xspaceagi.repo.application.service;

import com.xspaceagi.repo.application.service.dto.RepoPageVersionListResult;
import com.xspaceagi.repo.domain.model.RepoPageVersionDomain;

/**
 * 文档版本应用服务。
 * <p>
 * 所有操作要求当前用户为页面所属空间成员；版本数据权限随页面走。
 */
public interface RepoPageVersionApplicationService {

    /**
     * 分页查询页面版本列表（按 id 倒序，不含二进制/HTML大字段）
     *
     * @param pageId 页面ID
     * @param page   页码，从1开始，可空默认1
     * @param size   分页大小，可空默认20，上限100
     * @return 分页结果
     */
    RepoPageVersionListResult listVersions(Long pageId, Integer page, Integer size);

    /**
     * 查询版本HTML快照（预览用）
     *
     * @param pageId    页面ID
     * @param versionId 版本ID
     * @return 版本领域模型（含 html，不含 ydocSnapshot）
     */
    RepoPageVersionDomain getVersionPreview(Long pageId, Long versionId);

    /**
     * 恢复版本：将版本的 Yjs 快照覆盖写回 repo_page.ydoc_state，
     * 并追加一条 reason=restore 的新版本记录。下次 Sidecar 加载文档即生效。
     *
     * @param pageId    页面ID
     * @param versionId 版本ID
     * @return 新生成的 restore 版本记录
     */
    RepoPageVersionDomain restoreVersion(Long pageId, Long versionId);

    /**
     * 手动保存版本：读取当前 repo_page.ydoc_state 生成 reason=manual 版本。
     *
     * @param pageId 页面ID
     * @return 新版本记录
     */
    RepoPageVersionDomain createManualVersion(Long pageId);
}
