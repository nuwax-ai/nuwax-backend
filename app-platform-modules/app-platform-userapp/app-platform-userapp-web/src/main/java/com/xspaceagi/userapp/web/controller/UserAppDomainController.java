package com.xspaceagi.userapp.web.controller;

import java.net.InetAddress;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.SpacePermissionException;
import com.xspaceagi.userapp.application.service.IUserAppDomainApplicationService;
import com.xspaceagi.userapp.domain.model.UserAppDomainModel;
import com.xspaceagi.userapp.sdk.IUserAppRpcService;
import com.xspaceagi.userapp.sdk.dto.UserAppDto;
import com.xspaceagi.userapp.web.dto.UserAppDomainCreateReq;
import com.xspaceagi.userapp.web.dto.UserAppDomainDeleteReq;
import com.xspaceagi.userapp.web.dto.UserAppDomainUpdateReq;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

@Tag(name = "User app", description = "User app custom domain binding APIs")
@RestController
@RequestMapping("/api/userapp/domain")
@Slf4j
public class UserAppDomainController extends BaseController {

    /** 域名格式 */
    private static final Pattern DOMAIN_PATTERN =
            Pattern.compile("^([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,6}$");

    /** 官方接入域名（CNAME 指向校验用；不配置则跳过该校验）*/
    @Value("${custom-page.cnames:}")
    private List<String> cnames;

    @Resource
    private IUserAppDomainApplicationService userAppDomainApplicationService;
    @Resource
    private IUserAppRpcService userAppRpcService;
    @Resource
    private SpacePermissionService spacePermissionService;

    @Operation(summary = "查询应用绑定的域名列表")
    @GetMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<List<UserAppDomainModel>> listByApp(@RequestParam("appId") Long appId) {
        try {
            getUser();
            // 查询域名不限制空间权限，因为本身不限制访问，要先通过此接口查询域名进行访问，比如前端访问 https://**/user-app/169
            // checkSpacePermissionByAppId(appId);
            return ReqResult.success(userAppDomainApplicationService.listByAppId(appId));
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[UserAppDomain] list failed, appId={}", appId, e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "绑定自有域名")
    @PostMapping(value = "/create", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<UserAppDomainModel> create(@RequestBody UserAppDomainCreateReq req) {
        log.info("[UserAppDomain] bind, appId={}, domain={}", req == null ? null : req.getAppId(),
                req == null ? null : req.getDomain());
        try {
            UserContext userContext = getUser();
            String domain = validateDomain(req == null ? null : req.getDomain());
            if (req == null || req.getAppId() == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappDomainInvalid);
            }
            checkCnamePointed(domain);
            checkSpacePermissionByAppId(req.getAppId());

            UserAppDomainModel model = new UserAppDomainModel();
            model.setAppId(req.getAppId());
            model.setDomain(domain);
            return userAppDomainApplicationService.create(model, userContext);
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[UserAppDomain] bind failed, appId={}", req == null ? null : req.getAppId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "换绑域名")
    @PostMapping(value = "/update", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<UserAppDomainModel> update(@RequestBody UserAppDomainUpdateReq req) {
        try {
            UserContext userContext = getUser();
            if (req == null || req.getId() == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappDomainNotFound);
            }
            String domain = validateDomain(req.getDomain());
            checkCnamePointed(domain);
            // 权限：按现有绑定记录对应的应用校验空间成员（域服务再做租户归属校验）
            UserAppDomainModel binding = getBinding(req.getId());
            checkSpacePermissionByAppId(binding.getAppId());

            UserAppDomainModel model = new UserAppDomainModel();
            model.setId(req.getId());
            model.setDomain(domain);
            return userAppDomainApplicationService.update(model, userContext);
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[UserAppDomain] update failed, id={}", req == null ? null : req.getId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    @Operation(summary = "解绑域名")
    @PostMapping(value = "/delete", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> delete(@RequestBody UserAppDomainDeleteReq req) {
        try {
            UserContext userContext = getUser();
            if (req == null || req.getId() == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappDomainNotFound);
            }
            UserAppDomainModel binding = getBinding(req.getId());
            checkSpacePermissionByAppId(binding.getAppId());
            return userAppDomainApplicationService.delete(req.getId(), userContext);
        } catch (SpacePermissionException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (BizException e) {
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[UserAppDomain] unbind failed, id={}", req == null ? null : req.getId(), e);
            return ReqResult.error(e.getMessage());
        }
    }

    private String validateDomain(String domain) {
        if (domain == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappDomainInvalid);
        }
        String trimmed = domain.trim().toLowerCase();
        if (!DOMAIN_PATTERN.matcher(trimmed).matches()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappDomainInvalid);
        }
        return trimmed;
    }

    private UserAppDomainModel getBinding(Long id) {
        UserAppDomainModel binding = userAppDomainApplicationService.getById(id);
        if (binding == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappDomainNotFound);
        }
        return binding;
    }

    private void checkSpacePermissionByAppId(Long appId) {
        UserAppDto dto = userAppRpcService.queryById(appId);
        if (dto == null) {
            throw new IllegalArgumentException("User app does not exist");
        }
        spacePermissionService.checkSpaceUserPermission(dto.getSpaceId());
    }

    /** 域名须先 CNAME 到官方接入地址；未配置接入点时跳过 */
    private void checkCnamePointed(String domain) {
        if (CollectionUtils.isEmpty(cnames)) {
            return;
        }
        String domainIp = resolveDns(domain);
        if (domainIp == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappDomainCnameNotPointed);
        }
        for (String cname : cnames) {
            if (domainIp.equals(resolveDns(cname))) {
                return;
            }
        }
        throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappDomainCnameNotPointed);
    }

    private static String resolveDns(String domain) {
        Object dns = SimpleJvmHashCache.getHash("dns", domain);
        if (dns != null) {
            return dns.toString();
        }
        String ip = null;
        try {
            InetAddress address = InetAddress.getByName(domain);
            ip = address.getHostAddress();
        } catch (Exception e) {
            // 解析失败视为未指向
        }
        if (ip != null) {
            SimpleJvmHashCache.putHash("dns", domain, ip, 600);
        }
        return ip;
    }
}
