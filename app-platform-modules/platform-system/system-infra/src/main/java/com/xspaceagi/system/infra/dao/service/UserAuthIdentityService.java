package com.xspaceagi.system.infra.dao.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xspaceagi.system.infra.dao.entity.UserAuthIdentity;

import java.util.List;

public interface UserAuthIdentityService extends IService<UserAuthIdentity> {

    /**
     * 按身份源 + 外部标识精确查找绑定（唯一键命中）
     */
    UserAuthIdentity getByIdpExternal(Long idpId, String externalId);

    /**
     * 按 unionid 查找任一绑定（微信跨应用识别同一人），按创建时间最早优先
     */
    UserAuthIdentity getFirstByTenantUnion(String unionId);

    /**
     * 查询用户的全部绑定
     */
    List<UserAuthIdentity> listByUser(Long userId);

    /**
     * 删除用户的全部绑定（用户删除时级联，释放外部身份可重新绑定/注册）
     */
    void deleteByUser(Long userId);
}
