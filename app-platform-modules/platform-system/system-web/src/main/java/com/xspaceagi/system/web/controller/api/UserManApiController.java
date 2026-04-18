package com.xspaceagi.system.web.controller.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.dto.UserQueryDto;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.dto.PageQueryVo;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.web.dto.UserAddDto;
import com.xspaceagi.system.web.dto.UserUpdateDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "开放API-用户管理相关接口")
@RestController
@RequestMapping("/api/v1/system/user")
public class UserManApiController {

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private AuthService authService;

    @Operation(summary = "分页查询用户列表")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<IPage<UserDto>> listQuery(@RequestBody PageQueryVo<UserQueryDto> pageQueryVo) {
        return ReqResult.success(userApplicationService.listQuery(pageQueryVo));
    }

    @Operation(summary = "查询用户详细信息")
    @RequestMapping(path = "/detail", method = RequestMethod.GET)
    public ReqResult<UserDto> detailQuery(@RequestParam(name = "uid", required = false) String uid,
                                          @RequestParam(name = "id", required = false) Long id,
                                          @RequestParam(name = "username", required = false) String username,
                                          @RequestParam(name = "email", required = false) String email,
                                          @RequestParam(name = "phone", required = false) String phone) {
        UserDto userDto = null;
        if (id != null) {
            userDto = userApplicationService.queryById(id);
        }
        if (userDto == null && StringUtils.isNotBlank(uid)) {
            userDto = userApplicationService.queryUserByUid(uid);
        }
        if (userDto == null && StringUtils.isNotBlank(username)) {
            userDto = userApplicationService.queryUserByUserName(username);
        }
        if (userDto == null && StringUtils.isNotBlank(email)) {
            userDto = userApplicationService.queryUserByEmail(email);
        }
        if (userDto == null && StringUtils.isNotBlank(phone)) {
            userDto = userApplicationService.queryUserByPhone(phone);
        }
        if (userDto == null) {
            throw new IllegalArgumentException("Invalid param");
        }
        return ReqResult.success(userDto);
    }

    @Operation(summary = "添加用户")
    @RequestMapping(path = "/add", method = RequestMethod.POST)
    public ReqResult<Long> addUser(@RequestBody UserAddDto userAddDto) {
        Assert.notNull(userAddDto, "Invalid param");
        Assert.isTrue(StringUtils.isNotBlank(userAddDto.getPhone()) || StringUtils.isNotBlank(userAddDto.getEmail()), "email and phone cannot be null at the same time");
        UserDto userDto = new UserDto();
        BeanUtils.copyProperties(userAddDto, userDto);
        if (StringUtils.isNotBlank(userAddDto.getPassword())) {
            userDto.setResetPass(1);
        }
        userApplicationService.add(userDto);
        return ReqResult.success(userDto.getId());
    }

    @Operation(summary = "更新用户信息")
    @RequestMapping(path = "/{id}/update", method = RequestMethod.POST)
    public ReqResult<Void> updateUserById(@PathVariable Long id, @RequestBody UserUpdateDto userUpdateDto) {
        UserDto userUpdate = new UserDto();
        userUpdate.setId(id);
        userUpdate.setAvatar(userUpdateDto.getAvatar());
        userUpdate.setUserName(userUpdateDto.getUserName());
        userUpdate.setNickName(userUpdateDto.getNickName());
        userUpdate.setPhone(userUpdateDto.getPhone());
        userUpdate.setEmail(userUpdateDto.getEmail());
        userUpdate.setRole(userUpdateDto.getRole());
        userUpdate.setPassword(userUpdateDto.getPassword());
        //写一个验证，以上字段不能同时为空
        Assert.isTrue(StringUtils.isNotBlank(userUpdateDto.getNickName()) || StringUtils.isNotBlank(userUpdateDto.getUserName())
                || StringUtils.isNotBlank(userUpdateDto.getEmail()) || StringUtils.isNotBlank(userUpdateDto.getPhone())
                || StringUtils.isNotBlank(userUpdateDto.getAvatar()) || userUpdateDto.getRole() != null
                || StringUtils.isNotBlank(userUpdateDto.getPassword()), "nickName, userName, email, phone, avatar, role, password  cannot be null at the same time");
        userApplicationService.update(userUpdate);
        return ReqResult.success();
    }

    @Operation(summary = "禁用用户")
    @RequestMapping(path = "/{id}/disable", method = RequestMethod.POST)
    public ReqResult<Void> disableUserById(@PathVariable Long id) {
        UserDto userUpdate = new UserDto();
        userUpdate.setId(id);
        userUpdate.setStatus(User.Status.Disabled);
        userApplicationService.update(userUpdate);
        return ReqResult.success();
    }

    @Operation(summary = "启用用户")
    @RequestMapping(path = "/{id}/enable", method = RequestMethod.POST)
    public ReqResult<Void> enableUserById(@PathVariable Long id) {
        UserDto userUpdate = new UserDto();
        userUpdate.setId(id);
        userUpdate.setStatus(User.Status.Enabled);
        userApplicationService.update(userUpdate);
        return ReqResult.success();
    }


    @Operation(summary = "查询用户空间列表")
    @RequestMapping(path = "/{id}/space/list", method = RequestMethod.GET)
    public ReqResult<List<SpaceDto>> spaceList(@PathVariable Long id) {
        List<SpaceDto> spaceDtoList = spaceApplicationService.queryListByUserId(id);
        return ReqResult.success(spaceDtoList);
    }

    @Operation(summary = "创建指定用户的认证ticket")
    @RequestMapping(path = "/{id}/ticket/create", method = RequestMethod.POST)
    public ReqResult<String> createTicket(@PathVariable Long id) {
        UserDto userDto = userApplicationService.queryById(id);
        if (userDto == null) {
            throw new IllegalArgumentException("Error user id");
        }
        String token = authService.createToken(userDto, "open-api-" + UUID.randomUUID().toString().replace("-", ""));
        String ticket = authService.newTicket(userDto, token);
        return ReqResult.success(ticket);
    }

    @Operation(summary = "清除用户通过ticket获得的认证")
    @RequestMapping(path = "/{id}/ticket/clear", method = RequestMethod.POST)
    public ReqResult<Void> clearAll(@PathVariable Long id) {
        authService.expireUserAllToken(id, "open-api-");
        return ReqResult.success();
    }
}