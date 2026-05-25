package com.xspaceagi.pay.application.support;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/** 支付模块与前端/管理台约定的东八区墙钟时间与 {@link Date} 互转（用于筛选条件与 DB datetime 对齐）。 */
public final class PayShanghaiDates {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private PayShanghaiDates() {}

    /** 将「东八区墙钟」{@link LocalDateTime} 转为 {@link Date}；null 保持 null。 */
    public static Date fromWallShanghai(LocalDateTime ldt) {
        if (ldt == null) {
            return null;
        }
        return Date.from(ldt.atZone(SHANGHAI).toInstant());
    }
}
