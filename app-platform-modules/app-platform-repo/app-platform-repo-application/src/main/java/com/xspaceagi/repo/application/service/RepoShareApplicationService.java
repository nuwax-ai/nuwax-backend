package com.xspaceagi.repo.application.service;

import com.xspaceagi.repo.application.service.dto.RepoAclStatus;
import com.xspaceagi.repo.application.service.dto.RepoShareLinkStatsVO;
import com.xspaceagi.repo.application.service.dto.RepoShareStatus;
import com.xspaceagi.repo.application.service.dto.RepoSpaceShareItemVO;
import com.xspaceagi.repo.domain.model.RepoPageAclDomain;
import com.xspaceagi.repo.domain.model.RepoShareLinkDomain;

import java.util.List;

/**
 * 文档分享与按人权限应用服务（需求4/需求7）。
 * <p>
 * 权限门槛统一经 {@link RepoAccessService}：
 * 查分享状况=可查看；发起分享=可分享；取消分享/删链接=可管理或记录创建者；
 * 文档级按人权限=可管理（源空间管理员/属主或页面创建者）。
 */
public interface RepoShareApplicationService {

    /**
     * 查询文档分享状况（空间分享 + 个人分享 + 分享链接）。任何可查看者可调用。
     */
    RepoShareStatus getShareStatus(Long pageId);

    /**
     * 分享文档到工作空间（权限分享而非拷贝，重复分享覆盖权限位）。
     * 目标空间必须是操作者自己所在空间（防猜ID分享到任意空间）。
     *
     * @param pageId         页面ID
     * @param targetSpaceIds 目标空间ID列表
     * @param permBits       权限位组合（必须含 VIEW，掩码到 15）
     */
    void shareToSpaces(Long pageId, List<Long> targetSpaceIds, Integer permBits);

    /**
     * 分享文档给个人（需求3：权限分享而非拷贝，重复分享覆盖权限位）。
     * 目标用户必须真实存在；不允许分享给自己；成功后给被分享者发站内通知（失败仅告警）。
     *
     * @param pageId        页面ID
     * @param targetUserIds 被分享人用户ID列表
     * @param permBits      权限位组合（必须含 VIEW，掩码到 PERM_FULL）
     */
    void shareToUsers(Long pageId, List<Long> targetUserIds, Integer permBits);

    /**
     * @ 提及默认授权：被提及的人自动获得「可查看 + 可评论」。
     * <p>
     * 与 {@link #shareToUsers} 的区别（不要合并成一份实现）：
     * <ul>
     *   <li><b>不做权限位裁剪</b>：固定 {@code VIEW | COMMENT}，不随提及者的权限位缩放——
     *       这是产品的兜底语义（提到谁，谁就该能打开并回一句）；</li>
     *   <li><b>不发站内通知</b>：一次编辑里 @ 十几个人会瞬间刷屏，提及本身已经说明了来意；</li>
     *   <li><b>幂等且只增不减</b>：已有分享记录按位并集，绝不把对方已有的编辑权限降级；</li>
     *   <li><b>空间成员直接跳过</b>：在文档所在空间里的人本来就能看。</li>
     * </ul>
     * 调用者需对文档可编辑或可评论（只读访客不能借 @ 给别人开权限）。
     *
     * @param pageId       页面ID
     * @param targetUserIds 被提及用户ID列表（可含自己，自己会被跳过）
     * @return 实际新增/更新时间权限的非空间成员人数
     */
    int grantMentionAccess(Long pageId, List<Long> targetUserIds);

    /**
     * 空间主页「接收分享」：分享到该空间的文档列表（仅空间成员；逐页裁决可查看，无权限静默过滤）。
     * 返回文档源空间名 + 分享者用户名，按分享时间倒序。
     */
    List<RepoSpaceShareItemVO> listSpaceReceivedShares(Long spaceId);

    /**
     * 空间主页「对外分享」：该空间的文档分享到其他空间的记录列表（仅空间成员）。
     * 返回目标空间名 + 分享者用户名，按分享时间倒序。
     */
    List<RepoSpaceShareItemVO> listSpaceSentShares(Long spaceId);

    /**
     * 取消单条空间分享（可管理者，或分享记录创建者）
     */
    void unshare(Long pageId, Long shareId);

    /**
     * 取消单条个人分享（需求3；可管理者，或分享记录创建者）
     */
    void unshareUser(Long pageId, Long shareUserId);

    /**
     * 修改单条空间分享的权限位（可管理者，或分享记录创建者；不得超出操作者自身权限）。
     * 不发通知、不改分享关系，只覆盖 perm_bits。
     */
    void updateSharePerm(Long pageId, Long shareId, Integer permBits);

    /**
     * 修改单条个人授权的权限位（含 @ 自动授权；可管理者，或分享记录创建者；不得超出操作者自身权限）。
     * 与 {@link #shareToUsers} 分开：改权限不重复发送站内通知。
     */
    void updateShareUserPerm(Long pageId, Long shareUserId, Integer permBits);

    /**
     * 取消该文档全部分享（空间 + 个人）+ 全部链接（仅可管理者）
     */
    void unshareAll(Long pageId);

    /**
     * 新建分享链接。
     *
     * @param pageId       页面ID
     * @param accessType   login=登录可访问 public=任何人可访问（含匿名）
     * @param allowComment 是否允许评论（需求5），1=允许 0=不允许，缺省为允许
     * @param permBits     链接授予权限位（本轮需求4：只读类，强制去编辑位；缺省仅查看）
     * @return 含 token 的链接记录
     */
    RepoShareLinkDomain createShareLink(Long pageId, String accessType, Integer allowComment, Integer permBits);

    /**
     * 更新分享链接的「是否允许评论」（需求5；可管理者，或链接创建者）
     */
    void updateShareLinkComment(Long pageId, Long linkId, Integer allowComment);

    /**
     * 删除分享链接（可管理者，或链接创建者）
     */
    void deleteShareLink(Long pageId, Long linkId);

    /**
     * 查询分享链接访问统计（可查看者）。返回访问次数（PV）与去重访问用户列表（含最近访问时间）。
     */
    RepoShareLinkStatsVO getShareLinkStats(Long pageId, Long linkId);

    /**
     * 查询文档级按人权限状况（仅可管理者）
     */
    RepoAclStatus getAcl(Long pageId);

    /**
     * 整体覆盖文档级按人权限（仅可管理者）。
     * <p>
     * 开启后未配置成员降为仅阅读（用户已确认语义），创建者/源空间管理员不受影响。
     *
     * @param pageId     页面ID
     * @param aclEnabled 0=关闭 1=开启
     * @param entries    权限条目（整体覆盖；每条必须含 VIEW）
     */
    void putAcl(Long pageId, Integer aclEnabled, List<RepoPageAclDomain> entries);
}
