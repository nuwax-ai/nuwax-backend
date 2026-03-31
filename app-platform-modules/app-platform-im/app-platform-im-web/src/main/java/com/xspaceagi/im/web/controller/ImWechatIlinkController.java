package com.xspaceagi.im.web.controller;

import com.xspaceagi.im.application.wechat.WechatIlinkQrService;
import com.xspaceagi.im.web.dto.ImWechatIlinkQrPollResponse;
import com.xspaceagi.im.web.dto.ImWechatIlinkQrStartResponse;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import static com.xspaceagi.system.spec.enums.ResourceEnum.IM_CONFIG_ADD;

/**
 * 微信 iLink：扫码获取二维码、查询上游状态（不落库）。保存请走 {@link com.xspaceagi.im.web.controller.ImChannelController#add}。
 */
@RestController
@RequestMapping("/api/im-config/wechat-ilink")
@Slf4j
@Tag(name = "微信 iLink")
public class ImWechatIlinkController {

    @Resource
    private WechatIlinkQrService wechatIlinkQrService;

    @RequireResource(IM_CONFIG_ADD)
    @PostMapping("/qr/start")
    @Operation(summary = "创建扫码会话并返回二维码", description = "")
    public ReqResult<ImWechatIlinkQrStartResponse> qrStart() {
        WechatIlinkQrService.QrStartResult r = wechatIlinkQrService.startSession();
        return ReqResult.success(ImWechatIlinkQrStartResponse.builder()
                .sessionId(r.getSessionId())
                .qrcode(r.getQrcode())
                .qrcodeImgContent(r.getQrcodeImgContent())
                .build());
    }

    @RequireResource(IM_CONFIG_ADD)
    @GetMapping("/qr/status")
    @Operation(summary = "查询扫码状态（可能长阻塞）", description = "返回 status、configData；落库请使用 POST /api/im-config/channel/add。")
    public ReqResult<ImWechatIlinkQrPollResponse> qrStatus(@RequestParam String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            throw new BizException("sessionId 不能为空");
        }
        WechatIlinkQrService.QrPollResult r = wechatIlinkQrService.pollStatus(sessionId);
        return ReqResult.success(ImWechatIlinkQrPollResponse.builder()
                .status(r.getStatus())
                .configData(r.getConfigData())
                .build());
    }

}
