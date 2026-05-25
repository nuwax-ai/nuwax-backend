package com.xspaceagi.subscription.infra.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.subscription.infra.dao.entity.UserSubscription;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

@Mapper
public interface UserSubscriptionMapper extends BaseMapper<UserSubscription> {

    @Select("SELECT * FROM user_subscription WHERE user_id = #{userId} AND plan_id = #{planId}")
    UserSubscription selectByUserIdAndPlanId(@Param("userId") Long userId, @Param("planId") Long planId);

    @Update("UPDATE user_subscription SET end_time = #{endTime}, modified = NOW() WHERE id = #{id}")
    int updateEndTime(@Param("id") Long id, @Param("endTime") Date endTime);

    @Update("UPDATE user_subscription SET start_time = #{startTime}, end_time = #{endTime}, status = #{status}, modified = NOW() WHERE id = #{id}")
    int updatePeriod(@Param("id") Long id, @Param("startTime") Date startTime,
                     @Param("endTime") Date endTime, @Param("status") Integer status);

    java.util.Map<String, Object> selectStatsByPlanBiz(@Param("bizType") String bizType,
                                                        @Param("bizId") String bizId,
                                                        @Param("todayStart") java.util.Date todayStart,
                                                        @Param("monthStart") java.util.Date monthStart);

    List<UserSubscription> selectByPlanBiz(@Param("bizType") String bizType,
                                           @Param("bizId") String bizId,
                                           @Param("offset") int offset,
                                           @Param("limit") int limit);

    Long countByPlanBiz(@Param("bizType") String bizType,
                        @Param("bizId") String bizId);

    @Update("UPDATE user_subscription SET call_used_count = 0, next_reset_time = #{nextResetTime}, modified = NOW() WHERE id = #{id}")
    int resetCallCount(@Param("id") Long id, @Param("nextResetTime") Date nextResetTime);

    @Update("UPDATE user_subscription SET call_used_count = call_used_count + #{count}, modified = NOW() WHERE id = #{id}")
    int incrementCallCount(@Param("id") Long id, @Param("count") Integer count);
}
