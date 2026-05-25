package com.xspaceagi.bill.infra.dao.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xspaceagi.bill.infra.dao.entity.BillResourceStat;

import java.util.List;

public interface IBillResourceStatService extends IService<BillResourceStat> {

    /**
     * 追加统计数据（按天），同一维度已存在则累加，否则新增
     */
    void appendStat(BillResourceStat stat);

    /**
     * 按条件查询统计数据
     */
    List<BillResourceStat> queryStats(Long tenantId, Long userId, String type,
                                      String targetType, Long targetId,
                                      String dtStart, String dtEnd,
                                      int offset, int limit);

    Long countStats(Long tenantId, Long userId, String type,
                    String targetType, Long targetId,
                    String dtStart, String dtEnd);
}