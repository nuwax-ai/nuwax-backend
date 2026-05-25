package com.xspaceagi.credit.app.service;

import com.xspaceagi.credit.sdk.dto.CreditPackageDTO;

import java.util.List;

public interface CreditPackageService {

    Long createPackage(CreditPackageDTO dto);

    boolean updatePackage(CreditPackageDTO dto);

    boolean deletePackage(Long id);

    CreditPackageDTO getPackageById(Long id);

    List<CreditPackageDTO> getPackageList(Integer status);
}
