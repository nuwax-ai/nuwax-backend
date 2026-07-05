package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.AgentWorkspaceApplicationService;
import com.xspaceagi.agent.core.adapter.dto.InitProjectTemplateDto;
import com.xspaceagi.agent.core.adapter.dto.AgentPublishVersionDto;
import com.xspaceagi.agent.core.adapter.dto.InstallProjectDto;
import com.xspaceagi.agent.core.adapter.dto.PackageAgentDto;
import com.xspaceagi.agent.web.ui.controller.base.BaseController;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Agent工作空间接口")
@RestController
@RequestMapping("/api/agent/workspace")
@Slf4j
public class AgentWorkspaceController extends BaseController {

    @Resource
    private AgentWorkspaceApplicationService agentWorkspaceApplicationService;

    @Operation(summary = "安装项目依赖")
    @PostMapping("/install-project")
    public ReqResult<Void> installProject(@RequestBody InstallProjectDto dto) {
        Assert.notNull(dto.getCId(), "Parameter cId is required.");
        Assert.notNull(dto.getProgrammingLanguage(), "Parameter programmingLanguage is required.");

        dto.setUserId(RequestContext.get().getUserId());
        agentWorkspaceApplicationService.installProject(dto);
        return ReqResult.success();
    }

    @PostMapping("/init-project-template")
    public ReqResult<Void> initProjectTemplate(@RequestBody InitProjectTemplateDto dto) {
        dto.setUserId(RequestContext.get().getUserId());
        agentWorkspaceApplicationService.initProjectTemplate(dto);
        return ReqResult.success();
    }

    @PostMapping("/package")
    public ReqResult<List<AgentPublishVersionDto>> packageAgent(@RequestBody PackageAgentDto dto) {
        dto.setUserId(RequestContext.get().getUserId());
        List<AgentPublishVersionDto> result = agentWorkspaceApplicationService.packageAgent(dto);
        return ReqResult.success(result);
    }
}
