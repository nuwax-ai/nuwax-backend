package com.xspaceagi.pay.domain.repository;

import com.xspaceagi.pay.domain.model.PayDeveloperAccountModel;
import com.xspaceagi.pay.domain.model.PayDeveloperAccountPageSlice;
import java.util.Date;
import java.util.List;
import java.util.Optional;

public interface PayDeveloperAccountRepository {

    Optional<PayDeveloperAccountModel> findById(long id);

    Optional<PayDeveloperAccountModel> findByTenantIdAndUserId(long tenantId, long userId);

    PayDeveloperAccountModel save(PayDeveloperAccountModel model);

    void deleteById(long id);

    PayDeveloperAccountPageSlice pageByTenantAndFilters(
            long tenantId,
            Long userIdEq,
            List<Long> userIdIn,
            Date createdStart,
            Date createdEnd,
            int page,
            int pageSize);
}
