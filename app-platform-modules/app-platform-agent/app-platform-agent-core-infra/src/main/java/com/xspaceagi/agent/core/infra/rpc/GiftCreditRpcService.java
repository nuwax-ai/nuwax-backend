package com.xspaceagi.agent.core.infra.rpc;

import com.xspaceagi.credit.sdk.dto.CreditAddRequest;
import com.xspaceagi.credit.sdk.rpc.ICreditRpcService;
import com.xspaceagi.credit.spec.enums.CreditTypeEnum;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.spec.utils.I18nUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

@Service
public class GiftCreditRpcService {

    @Resource
    private ICreditRpcService iCreditRpcService;

    public void checkAndSendGiftCredit(TenantConfigDto tenantConfigDto, Long userId) {
        int dailyGiftCredit = 0;
        if (tenantConfigDto.getEnableDailyGiftCredit() != null && tenantConfigDto.getEnableDailyGiftCredit() == 1) {
            dailyGiftCredit = tenantConfigDto.getDailyGiftCreditAmount() == null ? 0 : tenantConfigDto.getDailyGiftCreditAmount();
        }
        //每日积分发放，bizNo一样，只会发放一次
        if (dailyGiftCredit > 0) {
            String bizNo = "DGC" + userId + "-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            if (iCreditRpcService.bizNoAdded(bizNo)) {
                return;
            }
            CreditAddRequest request = new CreditAddRequest();
            request.setCreditType(CreditTypeEnum.ACTIVITY);
            request.setUserId(userId);
            request.setAmount(BigDecimal.valueOf(dailyGiftCredit));
            request.setTenantId(tenantConfigDto.getTenantId());
            request.setBizNo(bizNo);
            request.setExpireTime(Date.from(LocalDate.now().atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant()));
            request.setRemark(I18nUtil.systemMessage("Backend.Credit.DailyGift.Remark"));
            iCreditRpcService.addCredit(request);
        }
    }
}
