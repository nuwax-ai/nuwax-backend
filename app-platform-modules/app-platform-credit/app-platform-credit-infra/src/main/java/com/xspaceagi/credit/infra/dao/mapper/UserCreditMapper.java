package com.xspaceagi.credit.infra.dao.mapper;

import com.xspaceagi.credit.infra.dao.entity.UserCredit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Mapper
public interface UserCreditMapper {

    int insert(UserCredit userCredit);

    int updateById(UserCredit userCredit);

    UserCredit selectById(@Param("id") Long id);

    UserCredit selectByBatchNo(@Param("batchNo") String batchNo);

    List<UserCredit> selectByUserId(@Param("userId") Long userId);

    List<UserCredit> selectByUserIdAndType(@Param("userId") Long userId, @Param("creditType") Integer creditType);

    List<UserCredit> selectExpiredCredits(@Param("userId") Long userId, @Param("creditType") Integer creditType);

    List<UserCredit> selectValidCredits(@Param("userId") Long userId, @Param("creditType") Integer creditType);

    List<UserCredit> selectValidCreditsOrderByExpireTime(@Param("userId") Long userId);

    BigDecimal sumRemainAmountByUserId(@Param("userId") Long userId);

    BigDecimal sumRemainAmountByUserIdAndType(@Param("userId") Long userId, @Param("creditType") Integer creditType);

    /**
     * 带乐观锁的余额更新，使用version字段保证原子性
     */
    @Update("UPDATE user_credit " +
            "SET remain_amount = remain_amount - #{deductAmount}, " +
            "    used_amount = used_amount + #{deductAmount}, " +
            "    version = version + 1, " +
            "    modified = NOW() " +
            "WHERE id = #{id} AND remain_amount >= #{deductAmount} AND version = #{version}")
    int updateRemainAmountWithVersion(@Param("id") Long id,
                                     @Param("deductAmount") BigDecimal deductAmount,
                                     @Param("version") Integer version);


    List<java.util.Map<String, Object>> selectSummaryList(@Param("userId") Long userId, @Param("offset") Integer offset, @Param("limit") Integer limit);

    Long countSummaryList(@Param("userId") Long userId);

    List<UserCredit> selectUnpaidLoans(@Param("userId") Long userId);

    int updateRepayAmount(@Param("id") Long id,
                          @Param("repayAmount") BigDecimal repayAmount,
                          @Param("repayStatus") Integer repayStatus,
                          @Param("version") Integer version);

    /**
     * 获取指定时间内即将过期的积分数量
     */
    @Select("SELECT COALESCE(SUM(remain_amount), 0) FROM user_credit " +
            "WHERE user_id = #{userId} " +
            "AND expire_time IS NOT NULL " +
            "AND expire_time <= #{expireDate} " +
            "AND expire_time > NOW()")
    BigDecimal sumExpiringCredits(@Param("userId") Long userId, @Param("expireDate") Date expireDate);
}
