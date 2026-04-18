package com.xspaceagi.system.application.service.impl;

import com.xspaceagi.system.application.constant.I18nLangTagConstraints;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.application.dto.I18nLangAddDto;
import com.xspaceagi.system.application.dto.I18nLangDto;
import com.xspaceagi.system.application.dto.I18nLangUpdateDto;
import com.xspaceagi.system.application.service.I18nLangApplicationService;
import com.xspaceagi.system.domain.service.I18nDomainService;
import com.xspaceagi.system.domain.service.I18nLangDomainService;
import com.xspaceagi.system.infra.dao.entity.I18nLang;
import com.xspaceagi.system.spec.common.RequestContext;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 语言应用服务实现
 */
@Service
public class I18nLangApplicationServiceImpl implements I18nLangApplicationService {

    @Resource
    private I18nLangDomainService i18nLangDomainService;

    @Resource
    private I18nDomainService i18nDomainService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long add(I18nLangAddDto addDto) {
        Assert.hasText(addDto.getName(), "Parameter 'name' cannot be left blank.");
        Assert.hasText(addDto.getLang(), "Parameter 'lang' cannot be left blank.");
        String normalizedLang = I18nLangTagConstraints.tryNormalizeToStoredForm(addDto.getLang())
                .orElseThrow(() -> BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemI18nLangTagInvalid));
        boolean duplicate = i18nLangDomainService.queryAll().stream()
                .anyMatch(l -> l.getLang() != null
                        && I18nLangTagConstraints.sameLanguageTag(l.getLang(), normalizedLang));
        if (duplicate) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemI18nLangTagDuplicate);
        }
        I18nLang i18nLang = new I18nLang();
        BeanUtils.copyProperties(addDto, i18nLang);
        i18nLang.setLang(normalizedLang);
        i18nLangDomainService.add(i18nLang);
        i18nDomainService.initConfigsForNewLang(RequestContext.get().getTenantId(), i18nLang.getLang());
        return i18nLang.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        I18nLang lang = i18nLangDomainService.queryById(id);
        Assert.notNull(lang, "This ID does not exist.");
        i18nDomainService.deleteByLang(RequestContext.get().getTenantId(), lang.getLang());
        i18nLangDomainService.delete(id);
    }

    @Override
    public void update(I18nLangUpdateDto updateDto) {
        I18nLang i18nLang = new I18nLang();
        BeanUtils.copyProperties(updateDto, i18nLang);
        i18nLangDomainService.update(i18nLang);
    }

    @Override
    public void setDefault(Long id) {
        i18nLangDomainService.setDefault(id);
    }

    @Override
    public List<I18nLangDto> queryAll() {
        List<I18nLang> langList = i18nLangDomainService.queryAll();
        return langList.stream().map(lang -> {
            I18nLangDto dto = new I18nLangDto();
            BeanUtils.copyProperties(lang, dto);
            return dto;
        }).collect(Collectors.toList());
    }

    @Override
    public void updateSort(List<I18nLangDto> sortList) {
        Map<Long, Integer> sortMap = new HashMap<>();
        for (I18nLangDto dto : sortList) {
            sortMap.put(dto.getId(), dto.getSort());
        }
        i18nLangDomainService.updateSort(sortMap);
    }

    @Override
    public I18nLangDto queryById(Long id) {
        I18nLang lang = i18nLangDomainService.queryById(id);
        if (lang == null) {
            return null;
        }
        I18nLangDto dto = new I18nLangDto();
        BeanUtils.copyProperties(lang, dto);
        return dto;
    }

    @Override
    public I18nLangDto getDefault(Long tenantId) {
        I18nLang lang = i18nLangDomainService.getDefault(tenantId);
        if (lang == null) {
            return null;
        }
        I18nLangDto dto = new I18nLangDto();
        BeanUtils.copyProperties(lang, dto);
        return dto;
    }
}
