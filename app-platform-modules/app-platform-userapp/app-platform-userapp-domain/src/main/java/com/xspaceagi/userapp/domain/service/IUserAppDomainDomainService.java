package com.xspaceagi.userapp.domain.service;

import java.util.List;

import com.xspaceagi.userapp.domain.model.UserAppDomainModel;
import com.xspaceagi.userapp.sdk.dto.UserAppDomainType;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;

public interface IUserAppDomainDomainService {

    List<UserAppDomainModel> listByAppId(Long appId);

    UserAppDomainModel getById(Long id);

    ReqResult<UserAppDomainModel> create(UserAppDomainModel model, UserContext userContext);

    ReqResult<UserAppDomainModel> update(UserAppDomainModel model, UserContext userContext);

    ReqResult<Void> delete(Long id, UserContext userContext);

    List<String> listAllDomains();

    /**
     * 分配平台系统域名（Dev：创建时；Prod：发布时）。
     * 域名为 随机串 + domainSuffix（如 abc3f7k2m9-dev-inner.nuwao.com），随机前缀防按 appId 枚举遍历。
     * 幂等：应用已有同类型绑定不重复分配；随机串撞串换串重试。
     */
    void ensureSystemBinding(Long appId, Long tenantId, String domainSuffix, UserAppDomainType domainType);

    /**
     * 物理删除应用全部域名绑定（含系统域名与自有域名；应用清理路径专用，
     * 释放自定义域名使其可被其他应用重新绑定）
     */
    void deleteByAppId(Long appId);
}
