package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.ComputerPodApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ComputerPodResultDto;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.infra.component.agent.SandboxAgentClient;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Pod容器接口")
@RestController
@RequestMapping("/api/computer")
@Slf4j
public class ComputerPodController {

    @Resource
    private ComputerPodApplicationService computerPodApplicationService;

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private SandboxAgentClient sandboxAgentClient;

    @Operation(summary = "启动容器")
    @PostMapping(value = "/pod/ensure", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Object> podEnsure(@RequestParam("cId") Long cId) {
        ComputerPodResultDto result = computerPodApplicationService.ensurePod(cId);
        return buildResponse(result);
    }

    @Operation(summary = "重启容器(销毁后重建)")
    @PostMapping(value = "/pod/restart", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Object> podRestart(@RequestParam("cId") Long cId) {
        checkConversationPermission(cId);
        ComputerPodResultDto result = computerPodApplicationService.restart(cId);
        return buildResponse(result);
    }

    @Operation(summary = "停止智能体")
    @RequestMapping(path = "/agent/stop/{conversationId}", method = RequestMethod.POST)
    public ReqResult<Void> stopAgent(@PathVariable Long conversationId) {
        checkConversationPermission(conversationId);
        sandboxAgentClient.agentStop(String.valueOf(conversationId));
        return ReqResult.success();
    }

    @Operation(summary = "容器保活")
    @PostMapping(value = "/pod/keepalive", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Object> podKeepalive(@RequestParam("cId") Long cId) {
        ComputerPodResultDto result = computerPodApplicationService.keepalive(cId);
        return buildResponse(result);
    }

    @Operation(summary = "查询容器 VNC 服务状态")
    @GetMapping(value = "/pod/vnc-status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Object> podVncStatus(@RequestParam("cId") Long cId) {
        ComputerPodResultDto result = computerPodApplicationService.getVncStatus(cId);
        return buildResponse(result);
    }

    /**
     * 校验会话是否属于当前登录用户，不属于则抛出无权限异常
     */
    private void checkConversationPermission(Long cId) {
        if (cId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "cId");
        }
        ConversationDto conversation = conversationApplicationService.getConversationByCid(cId);
        if (conversation == null
                || !RequestContext.get().getUserId().equals(conversation.getUserId())) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }
    }

    private ReqResult<Object> buildResponse(ComputerPodResultDto result) {
        if (result == null) {
            return ReqResult.error("Container service invocation failed");
        }

        String code = result.getCode();
        String message = result.getMessage();
        Object data = result.getData();

        // 根据 code 判断是否成功
        if ("0000".equals(code)) {
            return ReqResult.create(code, message, data);
        } else {
            // code 不是 0000，返回错误结果
            return ReqResult.create(code, code, message != null ? message : "Container operation failed", data);
        }
    }

}