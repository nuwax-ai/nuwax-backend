package com.xspaceagi.system.infra.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.system.infra.dao.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT COUNT(*) FROM user")
    Long countTotalUsers();

    @Select("SELECT COUNT(*) FROM user WHERE created >= CURDATE() AND created < CURDATE() + INTERVAL 1 DAY")
    Long countTodayNewUsers();

    @Select("SELECT DATE(created) as date, COUNT(*) as user_count " +
            "FROM user " +
            "WHERE created >= CURDATE() - INTERVAL 7 DAY " +
            "GROUP BY DATE(created) " +
            "ORDER BY date")
    List<Map<String, Object>> getLast7DaysNewUserTrend();

    @Select("SELECT DATE(created) as date, COUNT(*) as user_count " +
            "FROM user " +
            "WHERE created >= CURDATE() - INTERVAL 30 DAY " +
            "GROUP BY DATE(created) " +
            "ORDER BY date")
    List<Map<String, Object>> getLast30DaysNewUserTrend();

    @Select("SELECT DATE_FORMAT(created, '%Y-%m') as month, COUNT(*) as user_count " +
            "FROM user " +
            "WHERE created >= DATE_SUB(CURDATE(), INTERVAL 12 MONTH) " +
            "GROUP BY DATE_FORMAT(created, '%Y-%m') " +
            "ORDER BY month")
    List<Map<String, Object>> getMonthlyNewUserTrend();
}
