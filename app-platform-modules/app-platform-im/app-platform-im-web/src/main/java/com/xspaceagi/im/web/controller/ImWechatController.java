package com.xspaceagi.im.web.controller;

import com.xspaceagi.im.application.ImChannelConfigApplicationService;
import com.xspaceagi.im.application.WechatIlinkPushApplicationService;
import com.xspaceagi.im.application.dto.ImChannelConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/im/wechat")
@Slf4j
@Tag(name = "微信")
public class ImWechatController {

    @Resource
    private WechatIlinkPushApplicationService wechatIlinkPushApplicationService;
    @Resource
    private ImChannelConfigApplicationService imChannelConfigApplicationService;

    @PostMapping("/push-message")
    @Operation(summary = "向当前用户绑定的微信 iLink 会话推送文本消息")
    public ReqResult<Void> pushMessage(
            @RequestParam(required = false) String botId,
            @RequestParam String message) {
        if (StringUtils.isBlank(message)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "message");
        }
        UserDto userDto = (UserDto) RequestContext.get().getUser();
        if (userDto == null) {
            throw BizException.of(ErrorCodeEnum.UNAUTHORIZED, BizExceptionCodeEnum.userNotLoggedIn);
        }
        Long userId = userDto.getId();
        Long tenantId = userDto.getTenantId();
        ImChannelConfigDto configDto = imChannelConfigApplicationService.resolveWechatIlinkConfigIdForUserPush(tenantId, userId, botId);
        wechatIlinkPushApplicationService.pushText(configDto, message);
        return ReqResult.success();
    }

}
