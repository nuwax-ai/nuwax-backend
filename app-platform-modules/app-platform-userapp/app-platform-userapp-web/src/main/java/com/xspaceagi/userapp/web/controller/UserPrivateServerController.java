package com.xspaceagi.userapp.web.controller;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.userapp.application.service.IUserPrivateServerApplicationService;
import com.xspaceagi.userapp.domain.model.UserPrivateServerModel;
import com.xspaceagi.userapp.sdk.dto.UserPrivateServerDto;
import com.xspaceagi.userapp.web.dto.UserPrivateServerIdReq;
import com.xspaceagi.userapp.web.dto.UserPrivateServerSaveReq;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

@Tag(name = "User app", description = "User app private deploy server APIs")
@RestController
@RequestMapping("/api/userapp/private-server")
@Slf4j
public class UserPrivateServerController extends BaseController {

    @Resource
    private IUserPrivateServerApplicationService userPrivateServerApplicationService;

    @Operation(summary = "登记私有部署服务器")
    @PostMapping(value = "/create", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<UserPrivateServerDto> create(@RequestBody UserPrivateServerSaveReq req) {
        try {
            getUser();
            return ReqResult.success(userPrivateServerApplicationService.register(toModel(req)));
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[UserPrivateServer] create failed", e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "更新私服信息（变更地址/端口将回到待就绪状态，需重新健康检查通过）")
    @PostMapping(value = "/update", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<UserPrivateServerDto> update(@RequestBody UserPrivateServerSaveReq req) {
        try {
            UserContext user = getUser();
            if (req == null || req.getId() == null) {
                throw new IllegalArgumentException("id is required");
            }
            return ReqResult.success(userPrivateServerApplicationService.update(user.getUserId(), toModel(req)));
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[UserPrivateServer] update failed, id={}", req == null ? null : req.getId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "删除私服（仍被应用引用时拒绝）")
    @PostMapping(value = "/delete", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> delete(@RequestBody UserPrivateServerIdReq req) {
        try {
            UserContext user = getUser();
            if (req == null || req.getId() == null) {
                throw new IllegalArgumentException("id is required");
            }
            userPrivateServerApplicationService.delete(user.getUserId(), req.getId());
            return ReqResult.success();
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[UserPrivateServer] delete failed, id={}", req == null ? null : req.getId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "我的私服列表")
    @GetMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<List<UserPrivateServerDto>> list() {
        try {
            getUser();
            return ReqResult.success(userPrivateServerApplicationService.listMine());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[UserPrivateServer] list failed", e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "私服详情（属主可见 apiKey）")
    @GetMapping(value = "/get", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<UserPrivateServerDto> get(@RequestParam("id") Long id) {
        try {
            UserContext user = getUser();
            return ReqResult.success(userPrivateServerApplicationService.get(user.getUserId(), id));
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[UserPrivateServer] get failed, id={}", id, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "健康检查")
    @PostMapping(value = "/health-check", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Boolean> healthCheck(@RequestBody UserPrivateServerIdReq req) {
        try {
            UserContext user = getUser();
            if (req == null || req.getId() == null) {
                throw new IllegalArgumentException("id is required");
            }
            return ReqResult.success(userPrivateServerApplicationService.checkHealth(user.getUserId(), req.getId()));
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[UserPrivateServer] health check failed, id={}", req == null ? null : req.getId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    private UserPrivateServerModel toModel(UserPrivateServerSaveReq req) {
        UserPrivateServerModel model = new UserPrivateServerModel();
        model.setId(req.getId());
        model.setName(req.getName());
        model.setScheme(req.getScheme());
        model.setHost(req.getHost());
        model.setAgentPort(req.getAgentPort());
        model.setVncPort(req.getVncPort());
        model.setFileServerPort(req.getFileServerPort());
        model.setApiKey(req.getApiKey());
        return model;
    }
}
