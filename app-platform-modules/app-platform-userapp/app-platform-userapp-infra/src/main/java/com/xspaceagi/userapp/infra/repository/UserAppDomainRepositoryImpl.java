package com.xspaceagi.userapp.infra.repository;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xspaceagi.userapp.domain.model.UserAppDomainModel;
import com.xspaceagi.userapp.domain.repository.IUserAppDomainRepository;
import com.xspaceagi.userapp.infra.dao.entity.UserAppDomain;
import com.xspaceagi.userapp.infra.dao.service.IUserAppDomainDaoService;
import com.xspaceagi.userapp.infra.translator.IUserAppDomainTranslator;

import jakarta.annotation.Resource;

@Repository
public class UserAppDomainRepositoryImpl implements IUserAppDomainRepository {

    @Resource
    private IUserAppDomainDaoService userAppDomainDaoService;
    @Resource
    private IUserAppDomainTranslator userAppDomainTranslator;

    @Override
    public UserAppDomainModel getByDomain(String domain) {
        UserAppDomain entity = userAppDomainDaoService.getOne(Wrappers.<UserAppDomain>lambdaQuery()
                .eq(UserAppDomain::getDomain, domain)
                .last("limit 1"));
        return userAppDomainTranslator.convertToModel(entity);
    }

    @Override
    public UserAppDomainModel getById(Long id) {
        UserAppDomain entity = userAppDomainDaoService.getOne(Wrappers.<UserAppDomain>lambdaQuery()
                .eq(UserAppDomain::getId, id)
                .last("limit 1"));
        return userAppDomainTranslator.convertToModel(entity);
    }

    @Override
    public List<UserAppDomainModel> listByAppId(Long appId) {
        List<UserAppDomain> entities = userAppDomainDaoService.list(Wrappers.<UserAppDomain>lambdaQuery()
                .eq(UserAppDomain::getAppId, appId)
                .orderByDesc(UserAppDomain::getModified));
        return toModels(entities);
    }

    @Override
    public List<UserAppDomainModel> listAll() {
        return toModels(userAppDomainDaoService.list());
    }

    @Override
    public Long add(UserAppDomainModel model) {
        UserAppDomain entity = userAppDomainTranslator.convertToEntity(model);
        userAppDomainDaoService.save(entity);
        return entity.getId();
    }

    @Override
    public void updateById(UserAppDomainModel model) {
        UserAppDomain entity = userAppDomainTranslator.convertToEntity(model);
        userAppDomainDaoService.update(entity, Wrappers.<UserAppDomain>lambdaUpdate()
                .eq(UserAppDomain::getId, model.getId()));
    }

    @Override
    public void deleteById(Long id) {
        userAppDomainDaoService.removeById(id);
    }

    @Override
    public void deleteByAppId(Long appId) {
        userAppDomainDaoService.remove(Wrappers.<UserAppDomain>lambdaQuery()
                .eq(UserAppDomain::getAppId, appId));
    }

    private List<UserAppDomainModel> toModels(List<UserAppDomain> entities) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return entities.stream().map(userAppDomainTranslator::convertToModel).collect(Collectors.toList());
    }
}
