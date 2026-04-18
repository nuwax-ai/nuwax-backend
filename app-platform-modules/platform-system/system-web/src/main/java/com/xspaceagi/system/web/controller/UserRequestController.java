package com.xspaceagi.system.web.controller;

import com.xspaceagi.system.infra.dao.mapper.UserReqMapper;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.web.dto.UserAccessStatsDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.SYSTEM_DASHBOARD;

@Tag(name = "用户请求统计查询")
@RestController
@RequestMapping("/api/system/request")
public class UserRequestController {

    @Resource
    private UserReqMapper userReqMapper;

    @RequireResource(SYSTEM_DASHBOARD)
    @Operation(summary = "执行统计SQL")
    @RequestMapping(path = "/sql", method = RequestMethod.POST)
    public ReqResult<List<Map<String, Object>>> sqlExecute(@RequestBody Map<String, Object> params) throws JSQLParserException {
        Assert.notNull(params.get("sql"), "SQL cannot be left blank.");
        Assert.isTrue(params.get("sql").toString().toLowerCase().trim().startsWith("select"), "SQL must be start with select");
        //确保sql只能是查询
        Statement statement = CCJSqlParserUtil.parse(params.get("sql").toString());
        Select select = (Select) statement;
        PlainSelect plainSelect = select.getPlainSelect();
        if (plainSelect != null) {
            // 替换所有表名
            replaceTableName(plainSelect.getFromItem(), "user_req");
            if (plainSelect.getJoins() != null) {
                for (Join join : plainSelect.getJoins()) {
                    replaceTableName(join.getRightItem(), "user_req");
                }
            }
            // 检查是否有 LIMIT，如果没有则加上 LIMIT 1000
            if (plainSelect.getLimit() == null) {
                var limit = new Limit().withRowCount(new LongValue(1000));
                plainSelect.setLimit(limit);
            }
        }

        return ReqResult.success(userReqMapper.select(statement.toString()));
    }

    @RequireResource(SYSTEM_DASHBOARD)
    @Operation(summary = "获取用户访问统计")
    @RequestMapping(path = "/stats", method = RequestMethod.GET)
    public ReqResult<UserAccessStatsDto> getAccessStats() {
        Long todayUserCount = userReqMapper.countTodayUsers();
        Long last7DaysUserCount = userReqMapper.countLast7DaysUsers();
        Long last30DaysUserCount = userReqMapper.countLast30DaysUsers();

        List<UserAccessStatsDto.TrendItem> last7DaysTrend = userReqMapper.getLast7DaysTrend().stream()
                .map(UserAccessStatsDto::fromMap)
                .collect(Collectors.toList());

        UserAccessStatsDto stats = UserAccessStatsDto.builder()
                .todayUserCount(todayUserCount != null ? todayUserCount : 0L)
                .last7DaysUserCount(last7DaysUserCount != null ? last7DaysUserCount : 0L)
                .last30DaysUserCount(last30DaysUserCount != null ? last30DaysUserCount : 0L)
                .last7DaysTrend(last7DaysTrend)
                .build();

        return ReqResult.success(stats);
    }

    private static void replaceTableName(FromItem fromItem, String newTableName) {
        if (fromItem instanceof Table table) {
            table.setName(newTableName);
        } else if (fromItem instanceof LateralSubSelect lateral) {
            var select = lateral.getSelect();
            if (select instanceof PlainSelect subPlainSelect) {
                replaceTableName(subPlainSelect.getFromItem(), newTableName);
                if (subPlainSelect.getJoins() != null) {
                    for (Join join : subPlainSelect.getJoins()) {
                        replaceTableName(join.getRightItem(), newTableName);
                    }
                }
            }
        } else if (fromItem instanceof ParenthesedFromItem parenthesed) {
            replaceTableName(parenthesed.getFromItem(), newTableName);
        }
    }
}