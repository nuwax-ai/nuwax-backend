package com.xspaceagi.custompage.ui.web.controller;

import java.net.InetAddress;
import java.util.List;

import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.xspaceagi.custompage.application.service.ICustomPageConfigApplicationService;
import com.xspaceagi.custompage.application.service.ICustomPageDomainApplicationService;
import com.xspaceagi.custompage.domain.model.CustomPageConfigModel;
import com.xspaceagi.custompage.domain.model.CustomPageDomainModel;
import com.xspaceagi.custompage.ui.web.dto.CustomPageDomainCreateReq;
import com.xspaceagi.custompage.ui.web.dto.CustomPageDomainDeleteReq;
import com.xspaceagi.custompage.ui.web.dto.CustomPageDomainUpdateReq;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.SpacePermissionException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import static com.xspaceagi.system.spec.enums.ResourceEnum.PAGE_APP_BIND_DOMAIN;

@Tag(name = "Custom page domain binding", description = "APIs for custom page domain binding")
@RestController
@RequestMapping("/api/custom-page/domain")
@Slf4j
public class CustomPageDomainController extends BaseController {

    @Resource
    private ICustomPageDomainApplicationService customPageDomainApplicationService;

    @Resource
    private ICustomPageConfigApplicationService customPageConfigApplicationService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Value("${custom-page.cnames:}")
    private List<String> cnames;

    /**
     * 根据project_id查询域名列表
     * GET /api/custom-page-domain/list
     */
    @RequireResource(PAGE_APP_BIND_DOMAIN)
    @Operation(summary = "List domains by project ID")
    @GetMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<List<CustomPageDomainModel>> listByProject(@RequestParam("projectId") Long projectId) {
        log.info("[list By Project] querydomainlist, project Id={}", projectId);
        try {
            // 校验项目权限
            CustomPageConfigModel configModel = customPageConfigApplicationService.getByProjectId(projectId);
            if (configModel == null) {
                return ReqResult.error("0001", "Project does not exist");
            }
            spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

            List<CustomPageDomainModel> result = customPageDomainApplicationService.listByProjectId(projectId);

            log.info("[list By Project] querydomainlistsucceeded, project Id={}, count={}", projectId, result.size());
            return ReqResult.success(result);
        } catch (SpacePermissionException e) {
            log.error("[list By Project] querydomainlistfailed, project Id={}, {}", projectId, e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[list By Project] querydomainlistexception, project Id={}", projectId, e);
            return ReqResult.error("0000", "Query failed: " + e.getMessage());
        }
    }

    /**
     * 新增域名绑定
     * POST /api/custom-page-domain/create
     */
    @RequireResource(PAGE_APP_BIND_DOMAIN)
    @Operation(summary = "Create domain binding")
    @PostMapping(value = "/create", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<CustomPageDomainModel> create(@RequestBody CustomPageDomainCreateReq req) {
        log.info("[create] Create domain binding, project Id={}, domain={}", req.getProjectId(), req.getDomain());
        try {
            //写个正则校验域名合法性
            if (!req.getDomain().matches("^([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,6}$")) {
                return ReqResult.error("0001", "Invalid domain format");
            }
            if (CollectionUtils.isNotEmpty(cnames)) {
                boolean isBindOfficialCname = false;
                for (String cname : cnames) {
                    String cnameIp = resolveDns(cname);
                    String ip = resolveDns(req.getDomain());
                    if (cnameIp.equals(ip)) {
                        isBindOfficialCname = true;
                        break;
                    }
                }
                if (!isBindOfficialCname) {
                    return ReqResult.error("0001", "Point your domain to the official CNAME and try again after about 10 minutes");
                }
            }
            // 校验项目权限
            CustomPageConfigModel configModel = customPageConfigApplicationService.getByProjectId(req.getProjectId());
            if (configModel == null) {
                return ReqResult.error("0001", "Project does not exist");
            }
            spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

            UserContext userContext = getUser();

            CustomPageDomainModel model = new CustomPageDomainModel();
            model.setProjectId(req.getProjectId());
            model.setDomain(req.getDomain());

            ReqResult<CustomPageDomainModel> result = customPageDomainApplicationService.create(model, userContext);

            log.info("[create] createdomain bindingcompleted, project Id={}, domain={}, result={}",
                    req.getProjectId(), req.getDomain(), result.isSuccess());
            return result;
        } catch (SpacePermissionException e) {
            log.error("[create] createdomain bindingfailed, project Id={}, domain={}, {}", req.getProjectId(), req.getDomain(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[create] createdomain bindingexception, project Id={}, domain={}", req.getProjectId(), req.getDomain(), e);
            return ReqResult.error("0000", "Create failed: " + e.getMessage());
        }
    }

    /**
     * 修改域名绑定
     * POST /api/custom-page-domain/update
     */
    @RequireResource(PAGE_APP_BIND_DOMAIN)
    @Operation(summary = "Update domain binding")
    @PostMapping(value = "/update", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<CustomPageDomainModel> update(@RequestBody CustomPageDomainUpdateReq req) {
        log.info("[update] updatedomain binding, id={}, domain={}", req.getId(), req.getDomain());
        try {
            // 校验原记录的权限：通过id查询现有域名绑定
            CustomPageDomainModel existingDomain = customPageDomainApplicationService.getById(req.getId());
            if (existingDomain == null) {
                return ReqResult.error("0002", "Domain binding does not exist");
            }

            // 校验原projectId对应的项目权限
            CustomPageConfigModel existingConfigModel = customPageConfigApplicationService.getByProjectId(existingDomain.getProjectId());
            if (existingConfigModel == null) {
                return ReqResult.error("0001", "Original project does not exist");
            }
            spacePermissionService.checkSpaceUserPermission(existingConfigModel.getSpaceId());

            UserContext userContext = getUser();

            CustomPageDomainModel model = new CustomPageDomainModel();
            model.setId(req.getId());
            model.setDomain(req.getDomain());

            ReqResult<CustomPageDomainModel> result = customPageDomainApplicationService.update(model, userContext);

            log.info("[update] updatedomain bindingcompleted, id={}, result={}", req.getId(), result.isSuccess());
            return result;
        } catch (SpacePermissionException e) {
            log.error("[update] updatedomain bindingfailed, id={}, {}", req.getId(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[update] updatedomain bindingexception, id={}", req.getId(), e);
            return ReqResult.error("0000", "Update failed: " + e.getMessage());
        }
    }

    /**
     * 删除域名绑定
     * POST /api/custom-page-domain/delete
     */
    @RequireResource(PAGE_APP_BIND_DOMAIN)
    @Operation(summary = "Delete domain binding")
    @PostMapping(value = "/delete", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> delete(@RequestBody CustomPageDomainDeleteReq req) {
        log.info("[delete] Delete domain binding, id={}", req.getId());
        try {
            // 校验域名绑定是否存在并获取项目ID
            CustomPageDomainModel targetDomain = customPageDomainApplicationService.getById(req.getId());
            if (targetDomain == null) {
                return ReqResult.error("0002", "Domain binding does not exist");
            }

            // 校验项目权限
            CustomPageConfigModel configModel = customPageConfigApplicationService.getByProjectId(targetDomain.getProjectId());
            if (configModel == null) {
                return ReqResult.error("0001", "Project does not exist");
            }
            spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

            UserContext userContext = getUser();

            ReqResult<Void> result = customPageDomainApplicationService.delete(req.getId(), userContext);

            log.info("[delete] deletedomain bindingcompleted, id={}, result={}", req.getId(), result.isSuccess());
            return result;
        } catch (SpacePermissionException e) {
            log.error("[delete] deletedomain bindingfailed, id={}, {}", req.getId(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[delete] deletedomain bindingexception, id={}", req.getId(), e);
            return ReqResult.error("0000", "Delete failed: " + e.getMessage());
        }
    }

    private static String resolveDns(String domain) {
        Object dns = SimpleJvmHashCache.getHash("dns", domain);
        if (dns != null) {
            return dns.toString();
        }
        String ip = domain;
        try {
            InetAddress address = InetAddress.getByName(domain);
            ip = address.getHostAddress();
        } catch (Exception e) {
            return "";
        }
        SimpleJvmHashCache.putHash("dns", domain, ip, 600);
        return ip;
    }
}
