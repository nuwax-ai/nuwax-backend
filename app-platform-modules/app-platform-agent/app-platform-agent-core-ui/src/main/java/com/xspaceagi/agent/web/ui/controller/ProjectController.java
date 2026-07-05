package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.ProjectApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ProjectCreateDTO;
import com.xspaceagi.agent.core.adapter.dto.ProjectCreateResultDTO;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "项目相关接口")
@RestController
@RequestMapping("/api/project")
@Slf4j
public class ProjectController {

    public static final String DEFAULT_PROJECT_EN_NAME = "Unnamed project";

    public static final String DEFAULT_PROJECT_CN_NAME = "未命名项目";

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private ProjectApplicationService projectApplicationService;

    @Operation(summary = "创建项目")
    @RequestMapping(path = "/create", method = RequestMethod.POST)
    public ReqResult<ProjectCreateResultDTO> create(@RequestBody ProjectCreateDTO projectCreateDTO) {
        if (projectCreateDTO.getSpaceId() != null) {
            spacePermissionService.checkSpaceUserPermission(projectCreateDTO.getSpaceId());
        } else {
            List<SpaceDto> spaces = spaceApplicationService.queryListByUserId(RequestContext.get().getUserId());
            if (spaces.isEmpty()) {
                throw new BizException("Invalid user data");
            }
            SpaceDto spaceDto1 = spaces.stream().filter(spaceDto -> spaceDto.getType() == Space.Type.Personal).findFirst().orElse(spaces.get(0));
            projectCreateDTO.setSpaceId(spaceDto1.getId());
        }
        projectCreateDTO.setCreatorId(RequestContext.get().getUserId());
        if (StringUtils.isBlank(projectCreateDTO.getName())) {
            if ("en-US".equalsIgnoreCase(RequestContext.get().getLang())) {
                projectCreateDTO.setName(DEFAULT_PROJECT_EN_NAME);
            } else {
                projectCreateDTO.setName(DEFAULT_PROJECT_CN_NAME);
            }
        }
        return ReqResult.success(projectApplicationService.createProject(projectCreateDTO));
    }

}
