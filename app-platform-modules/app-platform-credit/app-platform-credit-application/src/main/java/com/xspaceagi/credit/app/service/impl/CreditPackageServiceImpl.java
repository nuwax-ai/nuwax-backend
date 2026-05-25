package com.xspaceagi.credit.app.service.impl;

import com.xspaceagi.credit.app.service.CreditPackageService;
import com.xspaceagi.credit.infra.dao.entity.CreditPackage;
import com.xspaceagi.credit.infra.dao.mapper.CreditPackageMapper;
import com.xspaceagi.credit.sdk.dto.CreditPackageDTO;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CreditPackageServiceImpl implements CreditPackageService {

    @Resource
    private CreditPackageMapper creditPackageMapper;

    @Override
    public Long createPackage(CreditPackageDTO dto) {
        CreditPackage creditPackage = new CreditPackage();
        BeanUtils.copyProperties(dto, creditPackage);

        if (creditPackage.getStatus() == null) {
            creditPackage.setStatus(1);
        }
        if (creditPackage.getSort() == null) {
            creditPackage.setSort(0);
        }

        creditPackageMapper.insert(creditPackage);
        return creditPackage.getId();
    }

    @Override
    public boolean updatePackage(CreditPackageDTO dto) {
        if (dto.getId() == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "plan id cannot be null");
        }

        CreditPackage creditPackage = new CreditPackage();
        BeanUtils.copyProperties(dto, creditPackage);

        int updated = creditPackageMapper.updateById(creditPackage);
        return updated > 0;
    }

    @Override
    public boolean deletePackage(Long id) {
        int deleted = creditPackageMapper.deleteById(id);
        return deleted > 0;
    }

    @Override
    public CreditPackageDTO getPackageById(Long id) {
        CreditPackage creditPackage = creditPackageMapper.selectById(id);
        if (creditPackage == null) {
            return null;
        }

        return convertToDTO(creditPackage);
    }

    @Override
    public List<CreditPackageDTO> getPackageList(Integer status) {
        List<CreditPackage> packages = creditPackageMapper.selectList(status);
        return packages.stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    private CreditPackageDTO convertToDTO(CreditPackage creditPackage) {
        CreditPackageDTO dto = new CreditPackageDTO();
        BeanUtils.copyProperties(creditPackage, dto);
        return dto;
    }
}
