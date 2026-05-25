package com.xspaceagi.pay.application.service;

import com.xspaceagi.pay.spec.dto.PageResult;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountAdminPageRequest;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountAdminUserQueryRequest;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountAdminSaveRequest;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountByIdRequest;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountResponse;

public interface PayDeveloperAccountAdminApplicationService {

    PayDeveloperAccountResponse save(PayDeveloperAccountAdminSaveRequest request);

    PayDeveloperAccountResponse getById(Long id);

    PayDeveloperAccountResponse getByUser(PayDeveloperAccountAdminUserQueryRequest request);

    void deleteById(PayDeveloperAccountByIdRequest request);

    PageResult<PayDeveloperAccountResponse> page(PayDeveloperAccountAdminPageRequest request);
}
