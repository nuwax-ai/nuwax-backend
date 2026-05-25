package com.xspaceagi.credit.api.rpc;

import com.xspaceagi.credit.app.service.CreditService;
import com.xspaceagi.credit.sdk.dto.CreditAddRequest;
import com.xspaceagi.credit.sdk.dto.CreditDeductRequest;
import com.xspaceagi.credit.sdk.dto.CreditRequest;
import com.xspaceagi.credit.sdk.dto.UserCreditSummary;
import com.xspaceagi.credit.sdk.rpc.ICreditRpcService;
import com.xspaceagi.system.spec.common.RequestContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.math.BigDecimal;

@Slf4j
@Service
public class CreditRpcService implements ICreditRpcService {

    @Resource
    private CreditService creditService;

    @Override
    public String addCredit(CreditAddRequest request) {
        assertRequest(request);
        if (RequestContext.get() == null) {
            RequestContext.setThreadTenantId(request.getTenantId());
            try {
                return creditService.addCredit(request);
            } finally {
                RequestContext.remove();
            }
        }
        return creditService.addCredit(request);
    }

    @Override
    public boolean deductCredit(CreditDeductRequest request) {
        assertRequest(request);
        if (RequestContext.get() == null) {
            RequestContext.setThreadTenantId(request.getTenantId());
            try {
                return creditService.deductCredit(request);
            } finally {
                RequestContext.remove();
            }
        }
        return creditService.deductCredit(request);
    }

    private void assertRequest(CreditRequest request) {
        Assert.notNull(request, "Request cannot be null");
        Assert.hasText(request.getBizNo(), "BizNo cannot be empty");
        Assert.notNull(request.getUserId(), "User ID cannot be empty");
        Assert.notNull(request.getTenantId(), "Tenant ID cannot be empty");
        Assert.notNull(request.getCreditType(), "Credit type cannot be empty");
        Assert.notNull(request.getAmount(), "Credit amount cannot be empty");
        Assert.isTrue(request.getAmount().compareTo(BigDecimal.ZERO) > 0, "Credit amount must be greater than 0");
    }

    @Override
    public UserCreditSummary getUserCreditSummary(Long userId) {
        return creditService.getUserCreditSummary(userId);
    }
}
