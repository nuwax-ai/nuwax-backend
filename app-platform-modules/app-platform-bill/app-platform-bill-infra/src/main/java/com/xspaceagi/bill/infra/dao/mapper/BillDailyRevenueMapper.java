package com.xspaceagi.bill.infra.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.bill.infra.dao.entity.BillDailyRevenue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface BillDailyRevenueMapper extends BaseMapper<BillDailyRevenue> {

    Map<String, Object> selectStatsByUserId(@Param("userId") Long userId,
                                            @Param("today") String today,
                                            @Param("monthStart") String monthStart);

    List<Map<String, Object>> selectUserRankings(@Param("monthStart") String monthStart,
                                                 @Param("monthEnd") String monthEnd,
                                                 @Param("userId") Long userId);

    Long countUserRankings(@Param("monthStart") String monthStart,
                           @Param("monthEnd") String monthEnd,
                           @Param("userId") Long userId);

    Map<String, Object> selectAdminStats(@Param("monthStart") String monthStart,
                                         @Param("monthEnd") String monthEnd,
                                         @Param("today") String today,
                                         @Param("userId") Long userId);

    List<Map<String, Object>> selectAdminDailyRevenues(@Param("monthStart") String monthStart,
                                                       @Param("monthEnd") String monthEnd,
                                                       @Param("userId") Long userId,
                                                       @Param("status") String status,
                                                       @Param("offset") int offset,
                                                       @Param("limit") int limit);

    Long countAdminDailyRevenues(@Param("monthStart") String monthStart,
                                  @Param("monthEnd") String monthEnd,
                                  @Param("userId") Long userId,
                                  @Param("status") String status);
}
