package com.xspaceagi.repo.application.service;

import com.xspaceagi.log.sdk.vo.SearchResult;
import com.xspaceagi.repo.domain.model.RepoPageDomain;
import com.xspaceagi.repo.domain.model.RepoPageTreeNode;

import java.util.List;

/**
 * 文档页面应用服务。
 * <p>
 * 访问裁决统一走 {@link RepoAccessService}（空间成员/分享/文档级按人权限/链接）：
 * 读路径要求 viewable，写路径要求 editable，删除要求 deletable。
 */
public interface RepoPageApplicationService {

    /**
     * 查询页面详情
     *
     * @param pageId 页面ID
     * @return 页面领域模型，不存在返回 null
     */
    RepoPageDomain getPage(Long pageId);

    /**
     * 查询页面详情（携带分享链接 token 裁决，本轮需求4：经链接进入的登录用户）
     *
     * @param pageId     页面ID
     * @param shareToken 分享链接 token，可空
     * @return 页面领域模型，不存在返回 null
     */
    RepoPageDomain getPage(Long pageId, String shareToken);

    /**
     * 按短链ID查询页面详情（对外路由入口）
     *
     * @param slugId 短链ID
     * @return 页面领域模型，不存在返回 null
     */
    RepoPageDomain getPageBySlug(String slugId);

    /**
     * 按短链ID查询页面详情（携带分享链接 token 裁决）
     *
     * @param slugId     短链ID
     * @param shareToken 分享链接 token，可空
     * @return 页面领域模型，不存在返回 null
     */
    RepoPageDomain getPageBySlug(String slugId, String shareToken);

    /**
     * 查询空间下的根节点页面列表
     *
     * @param spaceId 空间ID
     * @return 根页面列表
     */
    List<RepoPageDomain> listSpaceRootPages(Long spaceId);

    /**
     * 新建页面
     *
     * @param spaceId         空间ID
     * @param parentId        父页面ID，null=根节点
     * @param title           标题
     * @param pageType        页面类型（doc/sheet），null或空=doc，非法值抛参数异常
     * @param sourceExt       导入源文件扩展名（需求2），原生创建传 null
     * @param originalFileKey 导入时原件的文件 key（需求3 原始数据导出），原生创建传 null
     * @return 新页面领域模型
     */
    RepoPageDomain createPage(Long spaceId, Long parentId, String title, String pageType, String sourceExt,
                              String originalFileKey);

    /**
     * 创建副本（文档复制）：同级末尾新建页面，标题=原标题+(副本)，
     * 内容三件套（contentSnapshot/htmlSnapshot/ydocState）与 sourceExt/originalFileKey 原样拷贝。
     * 权限：可复制（PERM_COPY）。副本为最后一次保存的快照，不含未落库编辑。
     *
     * @param pageId 源页面ID
     * @return 副本页面领域模型
     */
    RepoPageDomain copyPage(Long pageId);

    /**
     * 创建副本（携带分享链接 token 裁决，本轮需求4：链接授予复制位时可用）。
     *
     * @param pageId     源页面ID
     * @param shareToken 分享链接 token，可空
     * @return 副本页面领域模型
     */
    RepoPageDomain copyPage(Long pageId, String shareToken);

    /**
     * 创建副本（可选择目标空间与目标文件夹，本轮需求：副本落到指定空间/文件夹）。
     *
     * @param pageId         源页面ID
     * @param shareToken     分享链接 token，可空
     * @param targetSpaceId  目标空间ID；null=沿用源空间（同级末尾），等于源空间也可
     * @param targetParentId 目标父页面ID（文件夹）；null=目标空间根级
     * @return 副本页面领域模型
     */
    RepoPageDomain copyPage(Long pageId, String shareToken, Long targetSpaceId, Long targetParentId);

    /**
     * 重命名页面
     *
     * @param pageId 页面ID
     * @param title  新标题（最长500）
     * @return 更新后的页面领域模型
     */
    RepoPageDomain renamePage(Long pageId, String title);

    /**
     * 软删除页面及其全部子孙页面。
     * 权限：空间管理员/属主或页面创建者。
     *
     * @param pageId 页面ID
     */
    void deletePage(Long pageId);

    /**
     * 删除空间时级联清理该空间下全部页面：软删页面 + 清分享/附件 + 删无引用文件 + 删搜索索引。
     * 供空间删除异步队列消费，无需权限校验（删空间入口已做属主校验）。
     *
     * @param spaceId 空间ID
     * @return 软删页面数
     */
    int deleteBySpaceId(Long spaceId);

    /**
     * 移动页面：变更父节点并落到目标父节点下 beforeId/afterId 之间的位置。
     * beforeId 与 afterId 均为 null 时追加到目标父节点末尾。
     *
     * @param pageId         被移动页面ID
     * @param targetParentId 目标父页面ID，null=根节点
     * @param beforeId       目标位置前一个兄弟页面ID，可空
     * @param afterId        目标位置后一个兄弟页面ID，可空
     * @return 更新后的页面领域模型
     */
    RepoPageDomain movePage(Long pageId, Long targetParentId, Long beforeId, Long afterId);

    /**
     * 查询指定页面的直接子页面（按 position 排序）
     *
     * @param pageId 页面ID
     * @return 子页面列表
     */
    List<RepoPageDomain> listChildren(Long pageId);

    /**
     * 查询空间完整页面树（按 position 排序）
     *
     * @param spaceId 空间ID
     * @return 根节点列表
     */
    List<RepoPageTreeNode> getSpaceTree(Long spaceId);

    /**
     * 回写内容快照（前端防抖触发，决策②由前端发起）。
     * 更新 html_snapshot/content_snapshot 并刷新最后编辑信息，随后重建搜索索引。
     *
     * @param pageId      页面ID
     * @param html        HTML快照
     * @param contentJson 编辑器内容JSON快照
     * @return 更新后的页面领域模型
     */
    RepoPageDomain updateSnapshot(Long pageId, String html, String contentJson);

    /**
     * 存量回填正文媒体字节数（image/attachment/video/audio size 合计）。
     * 遍历全部有效页面，按 content_snapshot 重算并写回 media_size 列；幂等，可重复执行。
     *
     * @return 回填页面数
     */
    int backfillMediaSizes();

    /**
     * 按关键字搜索当前租户下的页面（结果后置过滤：空间成员 或 页面被分享到用户所在空间）。
     *
     * @param spaceId 空间ID，可空=全租户
     * @param keyword 关键字，可空=仅列出
     * @param from    分页起始，可空默认0
     * @param size    分页大小，可空默认20
     * @return 搜索结果（items 中 document 为 RepoPageSearchDocument）
     */
    SearchResult searchPages(Long spaceId, String keyword, Integer from, Integer size);

    /**
     * 最近编辑页面（需求6门户首页）：用户有成员资格的空间内页面，
     * 或被分享到这些空间的页面，按编辑时间倒序。
     *
     * @param size 条数上限（&lt;=0 默认12，封顶50）
     * @return 页面列表
     */
    List<RepoPageDomain> getRecentPages(int size);
}
