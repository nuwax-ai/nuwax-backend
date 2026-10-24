package com.xspaceagi.system.infra.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.system.infra.dao.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 用户表统计查询。
 * <p>本接口 5 条 SQL 全部服务「用户」口径（总用户数 / 今日新增 / 新增趋势），
 * 因此一律限定 {@code account_type = 'user'}：智能体身份与群代理身份是虚拟主体，
 * 由系统自动创建而非真人注册，计入会使增长数据失真。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    String ACCOUNT_TYPE_FILTER = " account_type = '" + User.ACCOUNT_TYPE_USER + "'";

    @Select("SELECT COUNT(*) FROM user WHERE" + ACCOUNT_TYPE_FILTER)
    Long countTotalUsers();

    @Select("SELECT COUNT(*) FROM user WHERE created >= CURDATE() AND created < CURDATE() + INTERVAL 1 DAY" +
            " AND" + ACCOUNT_TYPE_FILTER)
    Long countTodayNewUsers();

    @Select("SELECT DATE(created) as date, COUNT(*) as user_count " +
            "FROM user " +
            "WHERE created >= CURDATE() - INTERVAL 7 DAY " +
            "AND" + ACCOUNT_TYPE_FILTER + " " +
            "GROUP BY DATE(created) " +
            "ORDER BY date")
    List<Map<String, Object>> getLast7DaysNewUserTrend();

    @Select("SELECT DATE(created) as date, COUNT(*) as user_count " +
            "FROM user " +
            "WHERE created >= CURDATE() - INTERVAL 30 DAY " +
            "AND" + ACCOUNT_TYPE_FILTER + " " +
            "GROUP BY DATE(created) " +
            "ORDER BY date")
    List<Map<String, Object>> getLast30DaysNewUserTrend();

    @Select("SELECT DATE_FORMAT(created, '%Y-%m') as month, COUNT(*) as user_count " +
            "FROM user " +
            "WHERE created >= DATE_SUB(CURDATE(), INTERVAL 12 MONTH) " +
            "AND" + ACCOUNT_TYPE_FILTER + " " +
            "GROUP BY DATE_FORMAT(created, '%Y-%m') " +
            "ORDER BY month")
    List<Map<String, Object>> getMonthlyNewUserTrend();
}
