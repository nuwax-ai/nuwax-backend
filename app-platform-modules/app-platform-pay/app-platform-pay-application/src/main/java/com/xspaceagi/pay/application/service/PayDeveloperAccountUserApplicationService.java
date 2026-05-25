package com.xspaceagi.pay.application.service;

import com.xspaceagi.pay.spec.dto.PayDeveloperAccountResponse;
import com.xspaceagi.pay.spec.dto.PayDeveloperAccountUserSaveRequest;

public interface PayDeveloperAccountUserApplicationService {

    PayDeveloperAccountResponse save(PayDeveloperAccountUserSaveRequest request);

    PayDeveloperAccountResponse getCurrent();
}
