package com.xspaceagi.im.web.controller.api;

import com.xspaceagi.im.application.ImProactivePushApplicationService;
import com.xspaceagi.im.application.ImUserBindingApplicationService;
import com.xspaceagi.im.application.dto.ImBindStartResult;
import com.xspaceagi.im.application.dto.ImBindStatusResult;
import com.xspaceagi.im.application.dto.ImUserBindingDto;
import com.xspaceagi.im.application.dto.ImUserBindingsView;
import com.xspaceagi.im.web.dto.ImBindConfirmRequest;
import com.xspaceagi.im.web.dto.ImBindStartRequest;
import com.xspaceagi.im.web.dto.ImNotifySendRequest;
import com.xspaceagi.im.web.dto.ImUnbindRequest;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

@Tag(name = "开放API-IM消息通知")
@RestController
@RequestMapping("/api/v1/im-notify")
@Slf4j
public class ImNotifyApiController {

    @Resource
    private ImUserBindingApplicationService imUserBindingApplicationService;
    @Resource
    private ImProactivePushApplicationService imProactivePushApplicationService;

    @Operation(summary = "开始绑定 IM 账号",
            description = "appReturnUrl 可选，默认租户 siteUrl+/api/v1/im-notify/bind/return（域名回跳）。授权成功后 302 到该地址并带 confirmToken，再调 bind/confirm")
    @PostMapping("/bind/start")
    public ReqResult<ImBindStartResult> bindStart(@RequestBody ImBindStartRequest request) {
        if (request == null || StringUtils.isBlank(request.getChannel())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "channel");
        }
        return ReqResult.success(imUserBindingApplicationService.startBind(
                request.getChannel(),
                request.getChannelConfigId(),
                request.getAppReturnUrl()));
    }

    @Operation(summary = "确认 IM 绑定",
            description = "使用 OAuth 回跳深链中的 bindId + confirmToken；须为发起绑定的同一用户")
    @PostMapping("/bind/confirm")
    public ReqResult<Void> bindConfirm(@RequestBody ImBindConfirmRequest request) {
        if (request == null || StringUtils.isBlank(request.getBindId())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "bindId");
        }
        if (StringUtils.isBlank(request.getConfirmToken())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "confirmToken");
        }
        imUserBindingApplicationService.confirmOauthBind(request.getBindId(), request.getConfirmToken());
        return ReqResult.success();
    }

    @Operation(summary = "查询绑定会话状态",
            description = "status=authorized 时返回 confirmToken，App 可轮询后调 bind/confirm（须为发起绑定的同一用户）")
    @GetMapping("/bind/status")
    public ReqResult<ImBindStatusResult> bindStatus(@RequestParam String bindId) {
        return ReqResult.success(imUserBindingApplicationService.bindStatus(bindId));
    }

    @Operation(summary = "查询当前用户 IM 绑定")
    @GetMapping("/binding")
    public ReqResult<ImUserBindingDto> binding(@RequestParam String channel) {
        Long userId = RequestContext.get().getUserId();
        return ReqResult.success(imUserBindingApplicationService.getBinding(userId, channel));
    }

    @Operation(summary = "查询当前用户 IM 绑定（可按渠道过滤）",
            description = "不传 channel 返回飞书/企微/钉钉全部；传 channel=feishu|wework|dingtalk 只返回该渠道")
    @GetMapping("/bindings")
    public ReqResult<ImUserBindingsView> bindings(@RequestParam(required = false) String channel) {
        Long userId = RequestContext.get().getUserId();
        return ReqResult.success(imUserBindingApplicationService.listBindings(userId, channel));
    }

    @Operation(summary = "解绑 IM 账号")
    @PostMapping("/unbind")
    public ReqResult<Void> unbind(@RequestBody ImUnbindRequest request) {
        if (request == null || StringUtils.isBlank(request.getChannel())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "channel");
        }
        imUserBindingApplicationService.unbind(RequestContext.get().getUserId(), request.getChannel());
        return ReqResult.success();
    }

    @Operation(summary = "主动推送 IM 消息",
            description = "推送到当前登录用户已绑定的 IM 账号。")
    @PostMapping("/send")
    public ReqResult<Void> send(@RequestBody ImNotifySendRequest request) {
        if (request == null || StringUtils.isBlank(request.getChannel())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "channel");
        }
        Long currentUserId = RequestContext.get().getUserId();
        Long targetUserId = request.getUserId() != null ? request.getUserId() : currentUserId;
        if (currentUserId == null || !currentUserId.equals(targetUserId)) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }
        imProactivePushApplicationService.send(
                request.getChannel(),
                targetUserId,
                request.getTitle(),
                request.getContent(),
                request.getChannelConfigId());
        return ReqResult.success();
    }
}
