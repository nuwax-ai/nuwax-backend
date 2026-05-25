package com.xspaceagi.pay.application.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class AmountUtils {

    private AmountUtils() {}

    public static BigDecimal fenToYuan(Long fen) {
        if (fen == null) {
            return null;
        }
        return BigDecimal.valueOf(fen).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
