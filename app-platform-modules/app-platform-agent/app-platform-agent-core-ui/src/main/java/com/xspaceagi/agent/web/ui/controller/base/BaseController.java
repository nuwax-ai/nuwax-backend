package com.xspaceagi.agent.web.ui.controller.base;

import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.infra.dao.entity.User;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public abstract class BaseController {


    @Resource
    private SpaceApplicationService spaceApplicationService;

    /**
     * 获取当前登录用户信息
     *
     * @return
     */
    public UserContext getUser() {
        var userDto = (UserDto) RequestContext.get().getUser();
        var tenantConfigDto = RequestContext.get().getTenantConfig();
        return UserContext.builder()
                .userId(userDto.getId())
                .userName(userDto.getUserName())
                .nickName(userDto.getNickName())
                .email(userDto.getEmail())
                .phone(userDto.getPhone())
                .status(userDto.getStatus() == User.Status.Enabled ? 1 : -1)
                .tenantId(userDto.getTenantId())
                .tenantName(null)
                .tenantConfig(tenantConfigDto)
                .orgId(null)
                .orgName(null)
                .roleType(null)
                .build();
    }


    /**
     * 查询用户有权限的空间,限制访问空间,比如工作流查询全部知识库,要限制用户有权限的空间下的知识库
     *
     * @return 有权限的空间id列表
     */
    public List<Long> obtainAuthSpaceIds() {
        var userId = RequestContext.get().getUserId();
        //查询用户有权限的空间,限制访问空间,比如工作流查询全部知识库,要限制用户有权限的空间下的知识库
        var spaceList = this.spaceApplicationService.queryListByUserId(userId);
        return spaceList.stream().
                map(SpaceDto::getId)
                .toList();
    }

}
