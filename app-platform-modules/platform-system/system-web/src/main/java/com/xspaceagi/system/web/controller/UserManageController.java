package com.xspaceagi.system.web.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.system.application.dto.SendNotifyMessageDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.dto.UserQueryDto;
import com.xspaceagi.system.application.service.NotifyMessageApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.NotifyMessage;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.infra.dao.mapper.UserMapper;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.PageQueryVo;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.web.dto.UserAddDto;
import com.xspaceagi.system.web.dto.UserStatsDto;
import com.xspaceagi.system.web.dto.UserUpdateDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "系统管理-用户管理相关接口")
@RestController
@RequestMapping("/api/system/user")
public class UserManageController {

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private NotifyMessageApplicationService notifyMessageApplicationService;

    @Resource
    private UserMapper userMapper;

    @RequireResource(USER_MANAGE_QUERY)
    @Operation(summary = "查询用户列表")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<IPage<UserDto>> listQuery(@RequestBody PageQueryVo<UserQueryDto> pageQueryVo) {
        checkAdmin();
        return ReqResult.success(userApplicationService.listQuery(pageQueryVo));
    }

    @RequireResource(SYSTEM_DASHBOARD)
    @Operation(summary = "获取用户统计")
    @RequestMapping(path = "/stats", method = RequestMethod.GET)
    public ReqResult<UserStatsDto> getUserStats() {
        checkAdmin();

        Long totalUserCount = userMapper.countTotalUsers();
        Long todayNewUserCount = userMapper.countTodayNewUsers();

        List<UserStatsDto.TrendItem> last7DaysTrend = userMapper.getLast7DaysNewUserTrend().stream()
                .map(UserStatsDto::fromMap)
                .collect(Collectors.toList());

        List<UserStatsDto.TrendItem> last30DaysTrend = userMapper.getLast30DaysNewUserTrend().stream()
                .map(UserStatsDto::fromMap)
                .collect(Collectors.toList());

        List<UserStatsDto.TrendItem> monthlyTrend = userMapper.getMonthlyNewUserTrend().stream()
                .map(UserStatsDto::fromMap)
                .collect(Collectors.toList());

        UserStatsDto stats = UserStatsDto.builder()
                .totalUserCount(totalUserCount != null ? totalUserCount : 0L)
                .todayNewUserCount(todayNewUserCount != null ? todayNewUserCount : 0L)
                .last7DaysTrend(last7DaysTrend)
                .last30DaysTrend(last30DaysTrend)
                .monthlyTrend(monthlyTrend)
                .build();

        return ReqResult.success(stats);
    }

    @RequireResource(USER_MANAGE_ADD)
    @Operation(summary = "添加用户")
    @RequestMapping(path = "/add", method = RequestMethod.POST)
    public ReqResult<Void> addUser(@RequestBody UserAddDto userAddDto) {
        checkAdmin();
        UserDto userDto = new UserDto();
        BeanUtils.copyProperties(userAddDto, userDto);
        if (StringUtils.isNotBlank(userAddDto.getPassword())) {
            userDto.setResetPass(1);
        }
        userApplicationService.add(userDto);
        return ReqResult.success();
    }

    @RequireResource(USER_MANAGE_MODIFY)
    @Operation(summary = "更新用户信息")
    @RequestMapping(path = "/updateById/{id}", method = RequestMethod.POST)
    public ReqResult<Void> updateUserById(@PathVariable Long id, @RequestBody UserUpdateDto userUpdateDto) {
        checkAdmin();
        UserDto userUpdate = new UserDto();
        userUpdate.setId(id);
        userUpdate.setAvatar(userUpdateDto.getAvatar());
        userUpdate.setNickName(userUpdateDto.getNickName());
        userUpdate.setUserName(userUpdateDto.getUserName());
        userUpdate.setPhone(userUpdateDto.getPhone());
        userUpdate.setEmail(userUpdateDto.getEmail());
        userUpdate.setRole(userUpdateDto.getRole());
        userUpdate.setPassword(userUpdateDto.getPassword());
        userApplicationService.update(userUpdate);
        return ReqResult.success();
    }

    @RequireResource(USER_MANAGE_DISABLE)
    @Operation(summary = "禁用用户")
    @RequestMapping(path = "/disable/{id}", method = RequestMethod.POST)
    public ReqResult<Void> disableUserById(@PathVariable Long id) {
        checkAdmin();
        UserDto userUpdate = new UserDto();
        userUpdate.setId(id);
        userUpdate.setStatus(User.Status.Disabled);
        userApplicationService.update(userUpdate);
        return ReqResult.success();
    }

    @RequireResource(USER_MANAGE_ENABLE)
    @Operation(summary = "启用用户")
    @RequestMapping(path = "/enable/{id}", method = RequestMethod.POST)
    public ReqResult<Void> enableUserById(@PathVariable Long id) {
        checkAdmin();
        UserDto userUpdate = new UserDto();
        userUpdate.setId(id);
        userUpdate.setStatus(User.Status.Enabled);
        userApplicationService.update(userUpdate);
        return ReqResult.success();
    }

    @RequireResource(USER_MANAGE_DISABLE)
    @Operation(summary = "删除用户")
    @RequestMapping(path = "/delete/{id}", method = RequestMethod.POST)
    public ReqResult<Void> deleteUserById(@PathVariable Long id) {
        checkAdmin();
        userApplicationService.logicDelete(id);
        return ReqResult.success();
    }

    @RequireResource(USER_MANAGE_SEND_MESSAGE)
    @Operation(summary = "发送通知消息")
    @RequestMapping(path = "/notify/message/send", method = RequestMethod.POST)
    public ReqResult<Void> notify(@RequestBody SendNotifyMessageDto notifyMessageDto) {
        notifyMessageDto.setSenderId(RequestContext.get().getUserId());
        if (CollectionUtils.isNotEmpty(notifyMessageDto.getUserIds())) {
            notifyMessageDto.setScope(NotifyMessage.MessageScope.System);
        } else {
            notifyMessageDto.setScope(NotifyMessage.MessageScope.Broadcast);
        }
        notifyMessageApplicationService.sendNotifyMessage(notifyMessageDto);
        return ReqResult.success();
    }

    /**
     * 当前简单的在商家范围支持普通用户和管理员两种角色；后续再迭代完整的权限角色
     */
    private void checkAdmin() {
        if (((UserDto) RequestContext.get().getUser()).getRole() != User.Role.Admin) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }
    }
}