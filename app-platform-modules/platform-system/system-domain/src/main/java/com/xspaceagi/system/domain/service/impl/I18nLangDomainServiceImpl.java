package com.xspaceagi.system.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.system.domain.service.I18nLangDomainService;
import com.xspaceagi.system.infra.dao.entity.I18nLang;
import com.xspaceagi.system.infra.dao.service.I18nLangService;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 语言表领域服务实现
 */
@Service
public class I18nLangDomainServiceImpl implements I18nLangDomainService {

    @Resource
    private I18nLangService i18nLangService;

    @Override
    public void add(I18nLang i18nLang) {
        Assert.notNull(i18nLang, "The parameter cannot be left blank.");
        Assert.notNull(i18nLang.getName(), "Parameter 'name' cannot be left blank.");
        Assert.notNull(i18nLang.getLang(), "Parameter 'lang' cannot be left blank.");

        // 检查语言标识是否已存在
        LambdaQueryWrapper<I18nLang> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(I18nLang::getLang, i18nLang.getLang());
        I18nLang existing = i18nLangService.getOne(queryWrapper);
        Assert.isNull(existing, "语言标识已存在：" + i18nLang.getLang());

        // 如果是默认语言，先将其他语言设为非默认
        if (i18nLang.getIsDefault() != null && i18nLang.getIsDefault() == 1) {
            setAllNonDefault();
        }

        i18nLangService.save(i18nLang);
    }

    @Override
    public void delete(Long id) {
        Assert.notNull(id, "Parameter 'id' cannot be left blank.");
        i18nLangService.removeById(id);
    }

    @Override
    public void update(I18nLang i18nLang) {
        Assert.notNull(i18nLang, "The parameter cannot be left blank.");
        Assert.notNull(i18nLang.getId(), "Parameter 'id' cannot be left blank.");

        // 查询原有记录
        I18nLang existing = i18nLangService.getById(i18nLang.getId());
        if (existing == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemI18nOriginalRecordNotFound);
        }

        // 不允许修改 lang 字段，保留原有值
        i18nLang.setLang(existing.getLang());

        // 如果设置为默认语言，先将其他语言设为非默认
        if (i18nLang.getIsDefault() != null && i18nLang.getIsDefault() == 1) {
            setAllNonDefault();
        }

        i18nLangService.updateById(i18nLang);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long id) {
        Assert.notNull(id, "Parameter 'id' cannot be left blank.");

        // 先将所有语言设为非默认
        setAllNonDefault();

        // 再将指定语言设为默认
        I18nLang i18nLang = new I18nLang();
        i18nLang.setId(id);
        i18nLang.setIsDefault(1);
        i18nLang.setStatus(1);
        i18nLangService.updateById(i18nLang);
    }

    @Override
    public List<I18nLang> queryAll() {
        LambdaQueryWrapper<I18nLang> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.orderByAsc(I18nLang::getSort, I18nLang::getId);
        return i18nLangService.list(queryWrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSort(Map<Long, Integer> sortMap) {
        Assert.notNull(sortMap, "Parameter 'sortMap' cannot be left blank.");

        List<I18nLang> updateList = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : sortMap.entrySet()) {
            I18nLang lang = new I18nLang();
            lang.setId(entry.getKey());
            lang.setSort(entry.getValue());
            updateList.add(lang);
        }

        i18nLangService.updateBatchById(updateList);
    }

    @Override
    public I18nLang queryById(Long id) {
        Assert.notNull(id, "Parameter 'id' cannot be left blank.");
        return i18nLangService.getById(id);
    }

    @Override
    public I18nLang getDefault(Long tenantId) {
        LambdaQueryWrapper<I18nLang> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(I18nLang::getTenantId, tenantId);
        queryWrapper.eq(I18nLang::getIsDefault, 1);
        queryWrapper.eq(I18nLang::getStatus, 1);
        queryWrapper.orderByAsc(I18nLang::getSort);
        queryWrapper.last("LIMIT 1");
        return TenantFunctions.callWithIgnoreCheck(() -> i18nLangService.getOne(queryWrapper));
    }

    /**
     * 将所有语言设为非默认
     */
    private void setAllNonDefault() {
        List<I18nLang> allLangs = i18nLangService.list();
        if (allLangs.isEmpty()) {
            return;
        }

        List<I18nLang> updateList = new ArrayList<>();
        for (I18nLang lang : allLangs) {
            if (lang.getIsDefault() != null && lang.getIsDefault() == 1) {
                I18nLang updateLang = new I18nLang();
                updateLang.setId(lang.getId());
                updateLang.setIsDefault(0);
                updateList.add(updateLang);
            }
        }

        if (!updateList.isEmpty()) {
            i18nLangService.updateBatchById(updateList);
        }
    }
}
