package com.xspaceagi.bill.app.service;

import com.xspaceagi.bill.sdk.dto.*;

public interface BillWithdrawAppService {

    WithdrawConfigDTO getWithdrawConfig(Long tenantId);

    boolean saveWithdrawConfig(SaveWithdrawConfigRequest request);

    WithdrawApplicationDTO createWithdrawApplication(Long tenantId, Long userId);

    WithdrawApplicationPageDTO queryWithdrawApplications(WithdrawQueryRequest query);

    boolean processWithdraw(WithdrawProcessRequest request);
}
