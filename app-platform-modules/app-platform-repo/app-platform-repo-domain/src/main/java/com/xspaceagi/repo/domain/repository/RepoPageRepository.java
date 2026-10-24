package com.xspaceagi.repo.domain.repository;

import com.xspaceagi.repo.domain.model.RepoPageDomain;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 文档页面仓储接口。
 * <p>
 * 租户隔离由平台 MyBatis-Plus TenantLineInnerInterceptor 统一注入 _tenant_id 条件，
 * 实现层无需显式携带租户参数。
 */
public interface RepoPageRepository {

    /**
     * 按主键查询页面（租户条件由拦截器注入）
     *
     * @param id 页面ID
     * @return 页面领域模型，不存在返回 null
     */
    RepoPageDomain findById(Long id);

    /**
     * 按短链ID查询页面（对外路由入口）
     *
     * @param slugId 短链ID
     * @return 页面领域模型，不存在返回 null
     */
    RepoPageDomain findBySlugId(String slugId);

    /**
     * 查询空间下的根节点页面列表（按 position 排序）
     *
     * @param spaceId 空间ID
     * @return 根页面列表
     */
    List<RepoPageDomain> listRootsBySpaceId(Long spaceId);

    /**
     * 查询指定父页面下的直接子页面列表（按 position 排序）
     *
     * @param spaceId  空间ID
     * @param parentId 父页面ID
     * @return 子页面列表
     */
    List<RepoPageDomain> listChildren(Long spaceId, Long parentId);

    /**
     * 查询空间下全部有效页面（用于一次性组装树/收集子树）
     *
     * @param spaceId 空间ID
     * @return 页面列表（未保证顺序）
     */
    List<RepoPageDomain> listBySpaceId(Long spaceId);

    /**
     * 新建页面
     *
     * @param domain 页面领域模型
     * @return 自增主键
     */
    Long insert(RepoPageDomain domain);

    /**
     * 重命名页面标题
     *
     * @param id    页面ID
     * @param title 新标题
     * @return 是否更新成功
     */
    boolean updateTitle(Long id, String title);

    /**
     * 移动页面：更新父节点与同级排序（parentId 允许为 null 表示移动到根）
     *
     * @param id       页面ID
     * @param parentId 目标父页面ID，null=根节点
     * @param position 目标排序键
     * @return 是否更新成功
     */
    boolean updateParentAndPosition(Long id, Long parentId, String position);

    /**
     * 批量软删除页面
     *
     * @param ids 页面ID集合
     * @return 受影响行数
     */
    int softDeleteByIds(List<Long> ids);

    /**
     * 回写内容快照（前端防抖触发），同时刷新最后编辑人、编辑时间与编辑者列表
     *
     * @param id              页面ID
     * @param contentSnapshot 编辑器内容JSON快照
     * @param htmlSnapshot    HTML快照（用于搜索/导出/预览）
     * @param mediaSize       正文媒体字节数（image/attachment/video/audio 合计）
     * @param lastEditorId    最后编辑人ID
     * @param editedAt        编辑时间
     * @param editorIdsJson   历史编辑者ID JSON数组（应用层维护去重与上限），可空
     * @return 是否更新成功
     */
    boolean updateSnapshot(Long id, String contentSnapshot, String htmlSnapshot, Long mediaSize, Long lastEditorId, java.util.Date editedAt, String editorIdsJson);

    /**
     * 覆盖单页正文媒体字节数（存量回填）
     *
     * @param id        页面ID
     * @param mediaSize 正文媒体字节数
     * @return 是否更新成功
     */
    boolean updateMediaSize(Long id, Long mediaSize);

    /**
     * 读取全部有效页面ID（存量回填媒体大小用）。
     * <p>
     * 只取主键，正文由调用方按 id 逐页 findById 取，避免一次性拉全库大字段（约 90MB）。
     *
     * @return 页面ID列表
     */
    List<Long> listIdsForMediaBackfill();

    /**
     * 筛出仍被「排除集合之外的有效页面」引用的文件 key（删除页面时判断能否物理删文件）。
     * <p>
     * 引用形态：original_file_key 等值，或正文/HTML 快照含「/api/f/{fileKey}」子串。
     * 副本与源页共享同一批 key，物理删除前必须查，否则删源页会连带弄坏副本。
     * 一次扫表判定全部 key，避免逐 key 退化成 N 次全表扫。
     *
     * @param fileKeys   待判定的文件 key 集合
     * @param excludeIds 排除的页面ID集合（本次待删子树），非空
     * @return 仍被引用的 key 子集；参数为空时返回空集合
     */
    Set<String> filterReferencedFileKeys(Collection<String> fileKeys, List<Long> excludeIds);

    /**
     * 更新文档级按人权限开关（需求7）
     */
    boolean updateAclEnabled(Long id, Integer aclEnabled);

    /**
     * 最近编辑页面（需求6门户）：限定空间集合内的页面，或被分享到该空间集合内空间的页面，
     * 按编辑时间倒序。
     *
     * @param spaceIds 空间ID集合（用户有成员资格的空间）
     * @param size     条数上限
     * @return 页面列表
     */
    List<RepoPageDomain> listRecent(List<Long> spaceIds, int size);

    /**
     * 批量按主键查询有效页面（门户合并查询用）
     *
     * @param ids 页面ID集合
     * @return 页面列表（未保证顺序）
     */
    List<RepoPageDomain> listByIds(List<Long> ids);

    /**
     * 我编辑过的页面（需求1门户最近访问）：editor_ids 含该用户或最后编辑人为该用户，
     * 按编辑时间倒序。LIKE 粗筛，调用方按 editorIds 精确过滤。
     *
     * @param userId 用户ID
     * @param size   条数上限
     * @return 页面列表
     */
    List<RepoPageDomain> listRecentByEditor(Long userId, int size);

    /**
     * 空间集合内的页面（纯成员空间，「我的资料」），按编辑时间倒序分页。
     * 与 listRecent 不同：不含被分享进空间的页面，避免与「与我共享」重叠。
     *
     * @param spaceIds 空间ID集合（用户有成员资格的空间）
     * @param from     偏移量
     * @param size     条数上限
     * @return 页面列表
     */
    List<RepoPageDomain> listBySpaceIds(List<Long> spaceIds, int from, int size);

    /**
     * 读取 Yjs 二进制权威源（仅版本管理场景使用：手动版本/恢复）
     *
     * @param id 页面ID
     * @return ydoc_state 二进制，页面从未协作过返回 null
     */
    byte[] findYdocStateById(Long id);

    /**
     * 覆盖 Yjs 二进制权威源（版本恢复场景），同时刷新最后编辑信息
     *
     * @param id           页面ID
     * @param ydocState    新的 Yjs 二进制
     * @param lastEditorId 操作人ID
     * @param editedAt     操作时间
     * @return 是否更新成功
     */
    boolean updateYdocState(Long id, byte[] ydocState, Long lastEditorId, java.util.Date editedAt);
}
