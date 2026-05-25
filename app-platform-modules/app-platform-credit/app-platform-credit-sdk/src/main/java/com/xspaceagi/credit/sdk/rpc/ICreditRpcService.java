package com.xspaceagi.credit.sdk.rpc;

import com.xspaceagi.credit.sdk.dto.CreditAddRequest;
import com.xspaceagi.credit.sdk.dto.CreditDeductRequest;
import com.xspaceagi.credit.sdk.dto.UserCreditSummary;

public interface ICreditRpcService {

    String addCredit(CreditAddRequest request);

    boolean deductCredit(CreditDeductRequest request);

    UserCreditSummary getUserCreditSummary(Long userId);
}
