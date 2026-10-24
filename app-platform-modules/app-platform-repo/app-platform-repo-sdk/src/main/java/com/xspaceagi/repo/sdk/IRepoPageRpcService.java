package com.xspaceagi.repo.sdk;

import com.xspaceagi.repo.sdk.dto.RepoPageCopyRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageCreateRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageDeleteRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageGetBySlugRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageGetRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageListChildrenRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageListRootRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageMoveRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageRecentRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageRenameRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageRpcDto;
import com.xspaceagi.repo.sdk.dto.RepoPageSearchRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageSearchRpcResult;
import com.xspaceagi.repo.sdk.dto.RepoPageSnapshotRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageTreeRequest;
import com.xspaceagi.repo.sdk.dto.RepoPageTreeNodeRpcDto;

import java.util.List;

/**
 * 资料库「目录 + 文档」管理 RPC 服务（SDK 接口）。
 * <p>
 * 供其他业务模块在 JVM 内 {@code @Resource} 注入，以指定用户身份操作资料库目录与文档。
 * 每个方法的请求体都继承 {@code RepoPageRpcRequest}，携带调用方显式传入的 userId + tenantId
 * （支持后台任务/以指定用户身份调用），实现层据此切换
 * {@link com.xspaceagi.system.spec.common.RequestContext} 后再执行权限裁决，权限语义与 HTTP 入口一致。
 * <p>
 * 暴露范围 = {@code RepoPageApplicationService} 的核心面（目录树 / 增删改查 / 移动 / 复制 / 搜索 / 最近），
 * 不含分享 / 评论 / 版本 / 附件 / 门户，也不含内部任务方法（deleteBySpaceId、backfillMediaSizes）。
 */
public interface IRepoPageRpcService {

    /**
     * 查询页面详情（携带权限裁决）。
     *
     * @return 页面投影，不存在或无权限按既有异常语义处理
     */
    RepoPageRpcDto getPage(RepoPageGetRequest request);

    /**
     * 按短链ID查询页面详情（对外路由入口）。
     */
    RepoPageRpcDto getPageBySlug(RepoPageGetBySlugRequest request);

    /**
     * 查询空间下的根节点页面列表。
     */
    List<RepoPageRpcDto> listSpaceRootPages(RepoPageListRootRequest request);

    /**
     * 新建页面。
     */
    RepoPageRpcDto createPage(RepoPageCreateRequest request);

    /**
     * 创建副本：targetSpaceId 与 targetParentId 均为 null 时=同级末尾复制（标题=原标题+(副本)），
     * 否则复制到指定目标空间/文件夹。
     */
    RepoPageRpcDto copyPage(RepoPageCopyRequest request);

    /**
     * 重命名页面。
     */
    RepoPageRpcDto renamePage(RepoPageRenameRequest request);

    /**
     * 软删除页面及其全部子孙页面。
     */
    void deletePage(RepoPageDeleteRequest request);

    /**
     * 移动页面：变更父节点并落到目标父节点下 beforeId/afterId 之间的位置。
     */
    RepoPageRpcDto movePage(RepoPageMoveRequest request);

    /**
     * 查询指定页面的直接子页面（按 position 排序）。
     */
    List<RepoPageRpcDto> listChildren(RepoPageListChildrenRequest request);

    /**
     * 查询空间完整页面树（按 position 排序）。
     */
    List<RepoPageTreeNodeRpcDto> getSpaceTree(RepoPageTreeRequest request);

    /**
     * 回写内容快照（更新 html_snapshot/content_snapshot 并刷新最后编辑信息，随后重建搜索索引）。
     */
    RepoPageRpcDto updateSnapshot(RepoPageSnapshotRequest request);

    /**
     * 按关键字搜索页面（结果后置过滤：空间成员 或 页面被分享到用户所在空间）。
     */
    RepoPageSearchRpcResult searchPages(RepoPageSearchRequest request);

    /**
     * 最近编辑页面（用户有成员资格的空间内页面，或被分享到这些空间的页面，按编辑时间倒序）。
     */
    List<RepoPageRpcDto> getRecentPages(RepoPageRecentRequest request);
}
