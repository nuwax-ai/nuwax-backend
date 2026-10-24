package com.xspaceagi.repo.infra.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.repo.infra.dao.entity.RepoPage;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 文档页面Mapper
 *
 * @description 针对表【repo_page(文档页面表)】的数据库操作Mapper
 * @Entity com.xspaceagi.repo.infra.dao.entity.RepoPage
 */
public interface RepoPageMapper extends BaseMapper<RepoPage> {

    /**
     * 查询空间下有效根节点页面（按 position 排序）。
     * 租户条件由平台 TenantLineInnerInterceptor 自动注入。
     *
     * @param spaceId 空间ID
     * @return 根页面列表
     */
    List<RepoPage> listRootsBySpaceId(@Param("spaceId") Long spaceId);

    /**
     * 按短链ID查询有效页面。
     * 租户条件由平台 TenantLineInnerInterceptor 自动注入。
     *
     * @param slugId 短链ID
     * @return 页面实体
     */
    RepoPage findBySlugId(@Param("slugId") String slugId);

    /**
     * 查询指定父页面下的直接子页面（按 position 排序）。
     *
     * @param spaceId  空间ID
     * @param parentId 父页面ID
     * @return 子页面列表
     */
    List<RepoPage> listChildren(@Param("spaceId") Long spaceId, @Param("parentId") Long parentId);

    /**
     * 查询空间下全部有效页面。
     *
     * @param spaceId 空间ID
     * @return 页面列表
     */
    List<RepoPage> listBySpaceId(@Param("spaceId") Long spaceId);

    /**
     * 重命名页面标题。
     *
     * @param id    页面ID
     * @param title 新标题
     * @return 受影响行数
     */
    int updateTitle(@Param("id") Long id, @Param("title") String title);

    /**
     * 移动页面：更新父节点与同级排序。
     *
     * @param id       页面ID
     * @param parentId 目标父页面ID（允许 null 表示根节点）
     * @param position 目标排序键
     * @return 受影响行数
     */
    int updateParentAndPosition(@Param("id") Long id, @Param("parentId") Long parentId, @Param("position") String position);

    /**
     * 批量软删除页面（置 yn=0）。
     *
     * @param ids 页面ID集合
     * @return 受影响行数
     */
    int softDeleteByIds(@Param("ids") List<Long> ids);

    /**
     * 回写内容快照并刷新最后编辑信息与编辑者列表。
     *
     * @param id              页面ID
     * @param contentSnapshot 编辑器内容JSON快照
     * @param htmlSnapshot    HTML快照
     * @param mediaSize       正文媒体字节数（image/attachment/video/audio 合计）
     * @param lastEditorId    最后编辑人ID
     * @param editedAt        编辑时间
     * @param editorIds       历史编辑者ID JSON数组，可空
     * @return 受影响行数
     */
    int updateSnapshot(@Param("id") Long id,
                       @Param("contentSnapshot") String contentSnapshot,
                       @Param("htmlSnapshot") String htmlSnapshot,
                       @Param("mediaSize") Long mediaSize,
                       @Param("lastEditorId") Long lastEditorId,
                       @Param("editedAt") java.util.Date editedAt,
                       @Param("editorIds") String editorIds);

    /**
     * 覆盖单页正文媒体字节数（存量回填）。
     *
     * @param id        页面ID
     * @param mediaSize 正文媒体字节数
     * @return 受影响行数
     */
    int updateMediaSize(@Param("id") Long id, @Param("mediaSize") Long mediaSize);

    /**
     * 读取全部有效页面ID（存量回填媒体大小用）。
     * <p>
     * 只取主键，正文由调用方按 id 逐页取，避免一次性拉全库
     * content_snapshot + html_snapshot（约 90MB）撑爆堆内存。
     *
     * @return 页面ID列表
     */
    List<Long> listIdsForMediaBackfill();

    /**
     * 统计除排除集合外，还有多少有效页面引用了该文件 key（删除时判断能否物理删文件）。
     * <p>
     * 引用形态三处：original_file_key 精确等值、content_snapshot / html_snapshot 内
     * 「/api/f/{fileKey}」子串。副本（copyPage）与源页共享同一批 key，故物理删除前必须查。
     * <p>
     * 一次扫表判定全部 key（逐 key 查会退化成 N 次全表扫，正文列合计约 90MB）：
     * 每个 key 出一列 k{下标}，值大于 0 表示仍被引用。
     *
     * @param fileKeys   待判定的文件 key 列表，非空
     * @param excludeIds 排除的页面ID集合（本次待删子树），非空
     * @return 单行结果，列名 k0..k{n-1}，值为引用页面数
     */
    Map<String, Object> countReferencesForFileKeys(@Param("fileKeys") List<String> fileKeys,
                                                   @Param("excludeIds") List<Long> excludeIds);

    /**
     * 更新文档级按人权限开关。
     */
    int updateAclEnabled(@Param("id") Long id, @Param("aclEnabled") Integer aclEnabled);

    /**
     * 最近编辑页面：空间集合内页面 ∪ 被分享到空间集合内空间的页面，按编辑时间倒序。
     *
     * @param spaceIds 空间ID集合
     * @param size     条数上限
     * @return 页面列表
     */
    List<RepoPage> listRecent(@Param("spaceIds") List<Long> spaceIds, @Param("size") int size);

    /**
     * 批量按主键查询有效页面（门户合并查询用）。
     *
     * @param ids 页面ID集合
     * @return 页面列表
     */
    List<RepoPage> listByIds(@Param("ids") List<Long> ids);

    /**
     * 我编辑过的页面：最后编辑人为该用户或 editor_ids 粗匹配，按编辑时间倒序。
     * <p>
     * editor_ids 为 JSON 数组字符串，LIKE 仅作粗筛（可能误命中子串），
     * 调用方需按解析后的 editorIds 精确过滤。
     *
     * @param userId 用户ID
     * @param size   条数上限
     * @return 页面列表
     */
    List<RepoPage> listRecentByEditor(@Param("userId") Long userId, @Param("size") int size);

    /**
     * 空间集合内的页面（纯成员空间，「我的资料」），按编辑时间倒序分页。
     *
     * @param spaceIds 空间ID集合
     * @param from     偏移量
     * @param size     条数上限
     * @return 页面列表
     */
    List<RepoPage> listBySpaceIds(@Param("spaceIds") List<Long> spaceIds, @Param("from") int from, @Param("size") int size);

    /**
     * 读取 ydoc_state 二进制（仅查出 ydoc_state 列，映射到实体的 ydocState 字段）。
     * <p>
     * 不能直接以 byte[] 作为返回类型：MyBatis 对数组返回类型一律按"多行查询"处理，
     * 会把整行 blob 塞入 byte 组件类型的数组元素位，抛 IllegalArgumentException(argument type mismatch)。
     *
     * @param id 页面ID
     * @return 页面行（仅 ydoc_state 列有值），不存在返回 null
     */
    RepoPage selectYdocStateById(@Param("id") Long id);

    /**
     * 覆盖 ydoc_state 二进制并刷新最后编辑信息（版本恢复）。
     *
     * @param id           页面ID
     * @param ydocState    新的 Yjs 二进制
     * @param lastEditorId 操作人ID
     * @param editedAt     操作时间
     * @return 受影响行数
     */
    int updateYdocState(@Param("id") Long id,
                        @Param("ydocState") byte[] ydocState,
                        @Param("lastEditorId") Long lastEditorId,
                        @Param("editedAt") java.util.Date editedAt);
}
