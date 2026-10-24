package com.xspaceagi.userapp.domain.repository;

import java.util.List;

import com.xspaceagi.userapp.domain.model.UserAppDomainModel;

public interface IUserAppDomainRepository {

    UserAppDomainModel getByDomain(String domain);

    UserAppDomainModel getById(Long id);

    List<UserAppDomainModel> listByAppId(Long appId);

    List<UserAppDomainModel> listAll();

    Long add(UserAppDomainModel model);

    void updateById(UserAppDomainModel model);

    void deleteById(Long id);

    /** 按应用删除全部域名绑定（应用物理删除清理路径，含 Dev/Prod 系统域名与 Custom 自有域名） */
    void deleteByAppId(Long appId);
}
