package com.xspaceagi.userapp.domain.repository;

import java.util.Date;
import java.util.List;

import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.userapp.domain.model.UserAppModel;
import com.xspaceagi.userapp.sdk.dto.UserAppDeployType;
import com.xspaceagi.userapp.sdk.dto.UserAppPublishStatus;
import com.xspaceagi.userapp.sdk.dto.BuildVersionDto;

public interface IUserAppRepository {

    UserAppModel getById(Long id);

    long countByCreator(Long creatorId);

    List<UserAppModel> list(UserAppModel query);

    SuperPage<UserAppModel> pageQuery(UserAppModel query, Long current, Long pageSize);

    Long add(UserAppModel model);

    void updateById(UserAppModel model);

    /**
     * 更新构建版本记录（仅版本记录；publish_status 由市场发布流程维护）。
     * 以读取时的 modified 做乐观锁（CAS，expectModified 必填），更新0行返回 false 表示并发冲突
     */
    boolean updateBuildVersions(Long id, List<BuildVersionDto> buildVersions, Date expectModified);

    /** 更新发布状态 */
    void updatePublishStatus(Long id, UserAppPublishStatus publishStatus);

    /** 标记预删除（pre_delete_time = 入参时间；NULL 语义为正常） */
    void markPreDelete(Long id, Date preDeleteTime);

    /** 预删除时间已到期（<= deadline）的项目（跨租户系统扫描，业务查询勿用） */
    List<UserAppModel> listPreDeleteDue(Date deadline);

    /** 是否仍有应用以私服模式引用该部署目标（删除私服前的引用检查；-1 云端占位不计入） */
    boolean existsByPrivateServerId(Long deployServerId);

    /** 显式更新部署目标（platform 时 deploy_server_id 置 NULL，不受实体更新忽略 null 影响） */
    void updateDeployTarget(Long appId, UserAppDeployType deployType, Long deployServerId);

    void deleteById(Long id);
}
