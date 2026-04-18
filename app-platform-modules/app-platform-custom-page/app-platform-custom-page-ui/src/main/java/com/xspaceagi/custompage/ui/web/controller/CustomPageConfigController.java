package com.xspaceagi.custompage.ui.web.controller;

import com.xspaceagi.agent.core.adapter.application.PluginApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.application.WorkflowApplicationService;
import com.xspaceagi.agent.core.adapter.dto.PublishedPermissionDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.custompage.application.service.ICustomPageBuildApplicationService;
import com.xspaceagi.custompage.application.service.ICustomPageConfigApplicationService;
import com.xspaceagi.custompage.domain.model.CustomPageBuildModel;
import com.xspaceagi.custompage.domain.model.CustomPageConfigModel;
import com.xspaceagi.custompage.domain.proxypath.ICustomPageProxyPathService;
import com.xspaceagi.custompage.sdk.ICustomPageRpcService;
import com.xspaceagi.custompage.sdk.dto.*;
import com.xspaceagi.custompage.ui.web.dto.*;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.SpacePermissionException;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "Web app", description = "Custom page web app APIs")
@RestController
@RequestMapping("/api/custom-page")
@Slf4j
@RequiredArgsConstructor
public class CustomPageConfigController extends BaseController {

    @Resource
    private PluginApplicationService pluginApplicationService;
    @Resource
    private WorkflowApplicationService workflowApplicationService;
    @Resource
    private SpacePermissionService spacePermissionService;
    @Resource
    private ICustomPageRpcService iCustomPageRpcService;
    @Resource
    private PublishApplicationService publishApplicationService;
    @Resource
    private ICustomPageConfigApplicationService customPageConfigApplicationService;
    @Resource
    private ICustomPageProxyPathService customPageProxyPathApplicationService;
    @Resource
    private ICustomPageBuildApplicationService customPageBuildApplicationService;

    @RequireResource(PAGE_APP_CREATE)
    @Operation(summary = "Create project", description = "Create a new project")
    @PostMapping(value = "/create", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<CustomPageCreateRes> create(@RequestBody CustomPageCreateReq req) {
        log.info("[Web] createprojectrequest, project Name={}", req.getProjectName());
        try {
            UserContext userContext = getUser();

            // 创建项目
            CustomPageConfigModel model = new CustomPageConfigModel();
            model.setName(req.getProjectName());
            model.setDescription(req.getProjectDesc());
            model.setSpaceId(req.getSpaceId());
            model.setIcon(req.getIcon());
            model.setCoverImg(req.getCoverImg());
            model.setCoverImgSourceType(req.getCoverImgSourceType());
            model.setNeedLogin(YesOrNoEnum.Y.getKey());
            model.setProjectType(ProjectType.ONLINE_DEPLOY);

            var result = customPageConfigApplicationService.create(model, userContext);
            if (!result.isSuccess()) {
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }
            log.info("[Web] createprojectsucceeded,startinitialize,project Id={}", result.getData().getId());

            // 初始化项目
            ReqResult<Map<String, Object>> initResult = customPageBuildApplicationService
                    .initProject(result.getData().getId(), userContext);
            log.info("[Web] project Id={},initialize project result,{}:{}, resp={}",
                    result.getData().getId(), initResult.getCode(), initResult.getMessage(), initResult.getData());

            if (!initResult.isSuccess()) {
                log.warn("[Web] project Id={},initializeprojectfailed,startdelete project",
                        result.getData().getId());
                customPageConfigApplicationService.deleteProject(result.getData().getId(), userContext);
                return ReqResult.error("9999", initResult.getMessage());
            }

            CustomPageCreateRes res = CustomPageCreateRes.builder().projectId(result.getData().getId()).build();
            return ReqResult.success(res);
        } catch (SpacePermissionException e) {
            log.error("[Web] create project failed, project Name={}, {}", req.getProjectName(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] create project failed, project Name={}", req.getProjectName(), e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_IMPORT)
    @Operation(summary = "Upload project", description = "Upload a zip to create or update a project")
    @PostMapping(value = "/upload-and-start", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<CustomPageCreateRes> uploadProject(@ModelAttribute CustomPageCreateReq req) {
        log.info("[Web] upload projectrequest, project Name={}, project Id={}", req.getProjectName(), req.getProjectId());
        try {
            UserContext userContext = getUser();
            CustomPageConfigModel configModel = null;
            boolean isInitProject = true;

            // 上传要先判断项目是否存在
            if (req.getProjectId() != null && req.getProjectId() > 0) {
                configModel = customPageConfigApplicationService.getByProjectId(req.getProjectId());
                if (configModel == null) {
                    return ReqResult.error("0001", "Project does not exist");
                }
                isInitProject = false;
            } else {
                // 创建新项目
                CustomPageConfigModel model = new CustomPageConfigModel();
                model.setName(req.getProjectName());
                model.setDescription(req.getProjectDesc());
                model.setSpaceId(req.getSpaceId());
                model.setIcon(req.getIcon());
                model.setCoverImg(req.getCoverImg());
                model.setCoverImgSourceType(req.getCoverImgSourceType());
                model.setNeedLogin(YesOrNoEnum.Y.getKey());
                model.setProjectType(ProjectType.ONLINE_DEPLOY);

                var result = customPageConfigApplicationService.create(model, userContext);
                if (!result.isSuccess()) {
                    return ReqResult.create(result.getCode(), result.getMessage(), null);
                }
                configModel = result.getData();
            }

            var result = customPageConfigApplicationService.uploadProject(configModel, req.getFile(), isInitProject,
                    userContext);
            if (!result.isSuccess()) {
                if (isInitProject) {
                    log.warn("[Web] project Id={},upload projectfailed,startdelete project", configModel.getId());
                    customPageConfigApplicationService.deleteProject(configModel.getId(), userContext);
                }
                return ReqResult.error("9999", "Upload project failed: " + result.getMessage());
            }

            CustomPageCreateRes res = CustomPageCreateRes.builder().projectId(configModel.getId()).build();
            if (result.getData().get("port") != null) {
                try {
                    res.setDevServerUrl(customPageProxyPathApplicationService.getDevProxyPath(configModel.getId()));
                } catch (Exception e) {
                    // 不抛出异常,前端上传成功即可
                }
            }

            return ReqResult.success(res);
        } catch (SpacePermissionException e) {
            log.error("[Web] upload projectfailed, project Name={}, project Id={}, {}", req.getProjectName(), req.getProjectId(),
                    e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] upload projectexception, project Name={}, project Id={}", req.getProjectName(), req.getProjectId(), e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_MODIFY)
    @Operation(summary = "Update project", description = "Update basic project information")
    @PostMapping(value = "/update-project", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> updateProject(@RequestBody CustomPageUpdateReq req) {
        log.info("[Web] modify projectrequest, project Id={}, project Name={}", req.getProjectId(), req.getProjectName());
        try {
            UserContext userContext = getUser();

            CustomPageConfigModel model = new CustomPageConfigModel();
            model.setId(req.getProjectId());
            model.setName(req.getProjectName());
            model.setDescription(req.getProjectDesc());
            model.setIcon(req.getIcon());
            model.setCoverImg(req.getCoverImg());
            model.setCoverImgSourceType(req.getCoverImgSourceType());
            model.setNeedLogin(req.getNeedLogin() == null ? null
                    : req.getNeedLogin() ? YesOrNoEnum.Y.getKey() : YesOrNoEnum.N.getKey());

            var result = customPageConfigApplicationService.updateProject(model, userContext);
            if (!result.isSuccess()) {
                log.warn("[Web] project Id={},modify projectfailed,message={}", req.getProjectId(), result.getMessage());
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            return ReqResult.success();
        } catch (SpacePermissionException e) {
            log.error("[Web] modify projectfailed, project Id={}, project Name={}, {}", req.getProjectId(), req.getProjectName(),
                    e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] modify projectfailed, project Id={}, project Name={}", req.getProjectId(), req.getProjectName(), e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_DELETE)
    @Operation(summary = "Delete project", description = "Delete a project")
    @PostMapping(value = "/delete-project", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> deleteProject(@RequestBody CustomPageDeleteReq req) {
        log.info("[Web] delete projectrequest, project Id={}", req.getProjectId());
        try {
            UserContext userContext = getUser();

            var result = customPageConfigApplicationService.deleteProject(req.getProjectId(), userContext);
            if (!result.isSuccess()) {
                log.warn("[Web] project Id={},delete projectfailed,message={}", req.getProjectId(), result.getMessage());
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            Map<String, Object> data = result.getData();
            CustomPageConfigModel config = (CustomPageConfigModel) data.get("config");
            CustomPageBuildModel build = (CustomPageBuildModel) data.get("build");
            // 删除远程的项目文件
            if (config.getProjectType() == ProjectType.ONLINE_DEPLOY) {
                customPageBuildApplicationService.deleteProjectFiles(build, userContext);
            }

            return ReqResult.success();
        } catch (SpacePermissionException e) {
            log.error("[Web] delete projectfailed, project Id={}, {}", req.getProjectId(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] delete projectfailed, project Id={}", req.getProjectId(), e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_COPY_TO_SPACE)
    @Operation(summary = "Copy project", description = "Copy project to target space")
    @PostMapping(value = "/copy-project", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<CustomPageCreateRes> copyProject(@RequestBody CustomPageCopyReq req) {
        log.info("[Web] copyprojectrequest, project Id={}, target Space Id={}", req.getProjectId(), req.getTargetSpaceId());
        Long projectId = req.getProjectId();
        try {
            UserContext userContext = getUser();

            CustomPageConfigModel sourceConfig = customPageConfigApplicationService.getByProjectId(projectId);
            if (sourceConfig == null) {
                log.error("[copy Project] source project not found, project Id={}", projectId);
                return ReqResult.error("0001", "Source project does not exist");
            }
            Long devAgentId = sourceConfig.getDevAgentId();
            Long targetSpaceId = req.getTargetSpaceId();

            //校验目标空间权限
            spacePermissionService.checkSpaceUserPermission(targetSpaceId);

            CopyTypeEnum copyType = req.getCopyType();
            if (copyType == null || copyType == CopyTypeEnum.SQUARE) {
                //广场复制
                //校验项目复制权限
                PublishedPermissionDto permissionDto = publishApplicationService.hasPermission(Published.TargetType.Agent, devAgentId);
                if (!permissionDto.isCopy()) {
                    throw new SpacePermissionException("You do not have permission to copy this app");
                }
            } else {
                //开发复制
                //校验源空间权限
                spacePermissionService.checkSpaceUserPermission(sourceConfig.getSpaceId());
            }

            // 创建新项目
            CustomPageConfigModel newConfigModel = new CustomPageConfigModel();
            newConfigModel.setName(sourceConfig.getName());
            newConfigModel.setDescription(sourceConfig.getDescription());
            newConfigModel.setIcon(sourceConfig.getIcon());
            newConfigModel.setCoverImg(sourceConfig.getCoverImg());
            newConfigModel.setCoverImgSourceType(sourceConfig.getCoverImgSourceType());
            newConfigModel.setNeedLogin(sourceConfig.getNeedLogin());
            newConfigModel.setProjectType(sourceConfig.getProjectType());
            newConfigModel.setProxyConfigs(sourceConfig.getProxyConfigs());
            newConfigModel.setPageArgConfigs(sourceConfig.getPageArgConfigs());
            newConfigModel.setExt(sourceConfig.getExt());
            newConfigModel.setSpaceId(targetSpaceId);

            ReqResult<CustomPageConfigModel> createResult = customPageConfigApplicationService.create(newConfigModel, userContext);
            if (!createResult.isSuccess()) {
                log.error("[copy Project] copyprojectfailed, message={}", createResult.getMessage());
                return ReqResult.error("0001", createResult.getMessage());
            }
            CustomPageConfigModel targetConfig = createResult.getData();

            //数据源复制
            List<DataSourceDto> newCreateDataSources = customPageConfigApplicationService.copyProjectDataSources(sourceConfig, targetConfig, userContext);

            //项目工程复制
            ReqResult<Map<String, Object>> copyResult = customPageBuildApplicationService.copyProject(sourceConfig.getId(), targetConfig.getId(), userContext);

            if (copyResult.isSuccess()) {
                CustomPageCreateRes res = new CustomPageCreateRes();
                res.setProjectId(targetConfig.getId());
                res.setSpaceId(targetSpaceId);
                return ReqResult.success();
            }

            //删除项目
            var deleteResult = customPageConfigApplicationService.deleteProject(targetConfig.getId(), userContext);
            if (!deleteResult.isSuccess()) {
                log.warn("[copy Project] target Project Id={},after copy project artifacts failed,delete projectfailed,message={}", targetConfig.getId(), deleteResult.getMessage());
            }
            //删除数据源
            if (newCreateDataSources != null && !newCreateDataSources.isEmpty()) {
                newCreateDataSources.forEach(dataSource -> {
                    String type = dataSource.getType();
                    Long id = dataSource.getId();
                    try {
                        if ("plugin".equals(type)) {
                            pluginApplicationService.delete(id);
                        } else if ("workflow".equals(type)) {
                            workflowApplicationService.delete(id);
                        }
                    } catch (Exception e) {
                        log.error("[copy Project] copyprojectfailed, project Id={}, target Space Id={},delete component failed: type={}, id={}",
                                req.getProjectId(), req.getTargetSpaceId(), type, id, e);
                    }
                });
            }

            return ReqResult.error(copyResult.getCode(), copyResult.getMessage());
        } catch (SpacePermissionException e) {
            log.error("[copy Project] copyprojectfailed, project Id={}, target Space Id={}, {}",
                    req.getProjectId(), req.getTargetSpaceId(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[copy Project] copyprojectfailed, project Id={}, target Space Id={}",
                    req.getProjectId(), req.getTargetSpaceId(), e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_CREATE)
    @Operation(summary = "Create reverse-proxy project", description = "Create a reverse-proxy type project")
    @PostMapping(value = "/create-reverse-proxy", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<CustomPageCreateRes> createReverseProxy(@RequestBody CustomPageCreateReq req) {
        log.info("[Web] create reverse proxy projectrequest, project Name={}", req.getProjectName());
        try {
            UserContext userContext = getUser();
            CustomPageConfigModel model = new CustomPageConfigModel();
            model.setName(req.getProjectName());
            model.setDescription(req.getProjectDesc());
            model.setSpaceId(req.getSpaceId());
            model.setIcon(req.getIcon());
            model.setProjectType(ProjectType.REVERSE_PROXY);

            var result = customPageConfigApplicationService.createReverseProxyProject(model, userContext);
            if (!result.isSuccess()) {
                log.warn("[Web] create reverse proxy projectfailed,message={}", result.getMessage());
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            CustomPageCreateRes res = CustomPageCreateRes.builder().projectId(result.getData().getId()).build();
            return ReqResult.success(res);
        } catch (SpacePermissionException e) {
            log.error("[Web] create reverse proxy projectfailed, project Name={}, {}", req.getProjectName(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] create reverse proxy projectfailed, project Name={}", req.getProjectName(), e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_QUERY_LIST)
    @Operation(summary = "List projects", description = "Query project list")
    @GetMapping(value = "/list-projects", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<List<CustomPageDto>> listProjects(CustomPageQueryReq req) {
        log.info("[Web] queryprojectlistrequest, req={}", req);
        try {
            List<CustomPageDto> listData = iCustomPageRpcService.list(req);
            return ReqResult.success(listData);
        } catch (Exception e) {
            log.error("[Web] queryprojectlistfailed", e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_QUERY_LIST)
    @Operation(summary = "Page query projects", description = "Paginated project query")
    @GetMapping(value = "/page-query-projects", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<SuperPage<CustomPageDto>> pageQueryProjects(
            @RequestBody PageQueryVo<CustomPageQueryReq> pageQueryVo) {
        log.info("[Web] pagedqueryprojectrequest, page Query Vo={}", pageQueryVo);
        try {
            CustomPageQueryReq req = pageQueryVo.getQueryFilter();
            if (req == null) {
                throw new IllegalArgumentException("Parameters are empty");
            }
            if (req.getSpaceId() == null) {
                throw new IllegalArgumentException("spaceId is required");
            }
            // 校验空间权限
            spacePermissionService.checkSpaceUserPermission(req.getSpaceId());
            SuperPage<CustomPageDto> page = iCustomPageRpcService.pageQuery(pageQueryVo);
            return ReqResult.success(page);
        } catch (Exception e) {
            log.error("[Web] pagedqueryprojectfailed", e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_QUERY_DETAIL)
    @Operation(summary = "Get project detail", description = "Get project details by project ID")
    @GetMapping(value = "/get-project-info", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<CustomPageDto> getProjectInfo(@RequestParam("projectId") Long projectId) {
        log.info("[Web] query project detail request, project Id={}", projectId);
        try {
            if (projectId == null || projectId <= 0) {
                return ReqResult.error("0001", "projectId is required or invalid");
            }

            CustomPageDto dto = iCustomPageRpcService.queryDetailWithVersion(projectId);
            if (dto == null) {
                return ReqResult.error("0002", "Project does not exist");
            }

            return ReqResult.success(dto);
        } catch (SpacePermissionException e) {
            log.error("[Web] query project detail, project Id={}, {}", projectId, e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] query project detailexception, project Id={}", projectId, e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_QUERY_DETAIL)
    @Operation(summary = "Get project by agentId", description = "Get project details by agent ID")
    @GetMapping(value = "/get-project-info-by-agent", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<CustomPageDto> getProjectInfoByAgentId(@RequestParam("agentId") Long agentId) {
        log.info("[Web] query project detail request, agent Id={}", agentId);
        try {
            if (agentId == null || agentId <= 0) {
                return ReqResult.error("0001", "agentId is required or invalid");
            }

            CustomPageDto dto = iCustomPageRpcService.queryDetailByAgentId(agentId);
            if (dto == null) {
                return ReqResult.error("0002", "Project does not exist");
            }

            return ReqResult.success(dto);
        } catch (SpacePermissionException e) {
            log.error("[Web] query project detail, agent Id={}, {}", agentId, e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] query project detailexception, agent Id={}", agentId, e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_QUERY_DETAIL)
    @Operation(summary = "Get project file content", description = "Query project workspace file content")
    @GetMapping(value = "/get-project-content", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<ProjectContentRes> getProjectContent(@RequestParam("projectId") Long projectId) {
        log.info("[Web] query project file contentrequest, project Id={}", projectId);
        try {
            String proxyPath = "/page/static/" + projectId;
            var result = customPageConfigApplicationService.queryProjectContent(projectId, proxyPath);
            if (!result.isSuccess()) {
                log.warn("[Web] project Id={},query project file contentfailed,message={}", projectId, result.getMessage());
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            Map<String, Object> data = result.getData();
            ProjectContentRes res = new ProjectContentRes();
            res.setFiles(data.get("files"));
            if (data.containsKey("frontendFramework")) {
                String frontendFramework = (String) data.get("frontendFramework");
                res.setFrontendFramework(frontendFramework);
            }
            if (data.containsKey("devFramework")) {
                String devFramework = (String) data.get("devFramework");
                res.setDevFramework(devFramework);
            }

            return ReqResult.success(res);
        } catch (SpacePermissionException e) {
            log.error("[Web] query project file contentfailed, project Id={}, {}", projectId, e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] query project file contentfailed, project Id={}", projectId, e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_QUERY_DETAIL)
    @Operation(summary = "Get historical project content", description = "Query project file content for a historical version")
    @GetMapping(value = "/get-project-content-by-version", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<ProjectContentRes> getProjectContentByVersion(@RequestParam("projectId") Long projectId,
                                                                   @RequestParam("codeVersion") Integer codeVersion) {
        log.info("[Web] queryprojecthistorical version contentrequest, project Id={}, code Version={}", projectId, codeVersion);
        try {
            if (projectId == null || projectId <= 0) {
                return ReqResult.error("0001", "projectId is required or invalid");
            }
            if (codeVersion == null || codeVersion < 0) {
                return ReqResult.error("0001", "Version number is required or invalid");
            }
            String proxyPath = "/page/static/_his/" + projectId;
            var result = customPageConfigApplicationService.queryProjectContentByVersion(projectId, codeVersion, proxyPath);
            if (!result.isSuccess()) {
                log.warn("[Web] project Id={},queryprojecthistorical version contentfailed,message={}", projectId, result.getMessage());
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            ProjectContentRes res = new ProjectContentRes();
            res.setFiles(result.getData().get("files"));

            return ReqResult.success(res);
        } catch (SpacePermissionException e) {
            log.error("[Web] queryprojecthistorical version contentfailed, project Id={}, code Version={}, {}", projectId, codeVersion, e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] queryprojecthistorical version contentfailed, project Id={}, code Version={}", projectId, codeVersion, e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_EXPORT)
    @Operation(summary = "Export project", description = "Export project as a zip file")
    @GetMapping(value = "/export-project")
    public ResponseEntity<byte[]> exportProject(@RequestParam("projectId") Long projectId) {
        log.info("[Web] exportprojectrequest, project Id={}", projectId);
        try {
            if (projectId == null || projectId <= 0) {
                return ResponseEntity.badRequest().build();
            }

            UserContext userContext = getUser();
            var result = customPageConfigApplicationService.exportProjectLatest(projectId, userContext);
            if (!result.isSuccess()) {
                log.warn("[Web] project Id={},exportprojectfailed,message={}", projectId, result.getMessage());
                return ResponseEntity.badRequest().build();
            }

            InputStream inputStream = result.getData();
            if (inputStream == null) {
                return ResponseEntity.notFound().build();
            }

            // 读取InputStream为byte数组
            byte[] bytes = inputStream.readAllBytes();
            inputStream.close();

            // 设置响应头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", "project_" + projectId + ".zip");
            headers.setContentLength(bytes.length);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(bytes);

        } catch (IOException e) {
            log.error("[Web] Export project error, project Id={}", projectId, e);
            return ResponseEntity.internalServerError().build();
        } catch (SpacePermissionException e) {
            log.error("[Web] exportprojectfailed, project Id={}, {}", projectId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        } catch (Exception e) {
            log.error("[Web] Export project error, project Id={}", projectId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @RequireResource(PAGE_APP_CONFIG_PROXY)
    @Operation(summary = "Batch configure reverse proxy", description = "Replace reverse-proxy configuration entirely")
    @PostMapping(value = "/batch-config-proxy", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> batchConfigProxy(@RequestBody ProxyConfigBatchReq req) {
        log.info("[Web] batch configure reverse proxyrequest, project Id={}, config Count={}",
                req.getProjectId(), req.getProxyConfigs() != null ? req.getProxyConfigs().size() : 0);
        try {
            UserContext userContext = getUser();

            // 转换对象
            List<ProxyConfig> proxyConfigs = req.getProxyConfigs().stream()
                    .map(item -> ProxyConfig.builder()
                            .env(ProxyConfig.ProxyEnv.get(item.getEnv()))
                            .path(item.getPath())
                            .healthCheckPath(item.getHealthCheckPath())
                            .requireAuth(item.getRequireAuth() != null ? item.getRequireAuth() : true)
                            .backends(item.getBackends().stream()
                                    .map(backendReq -> new ProxyConfigBackend(backendReq.getBackend(),
                                            backendReq.getWeight() != null ? backendReq.getWeight() : 1))
                                    .collect(Collectors.toList()))
                            .build())
                    .collect(Collectors.toList());

            var result = customPageConfigApplicationService.batchConfigProxy(req.getProjectId(), proxyConfigs,
                    userContext);
            if (!result.isSuccess()) {
                log.warn("[Web] project Id={},batch configure reverse proxyfailed,message={}", req.getProjectId(), result.getMessage());
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            return ReqResult.success();
        } catch (SpacePermissionException e) {
            log.error("[Web] batch configure reverse proxyfailed, project Id={}, {}", req.getProjectId(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] batch configure reverse proxyfailed, project Id={}", req.getProjectId(), e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_CONFIG_PATH)
    @Operation(summary = "Save path arguments", description = "Save path argument schema for a page URI")
    @PostMapping(value = "/save-path-args", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> savePathArgs(@RequestBody PageArgConfigReq req) {
        log.info("[Web] savepath request, project Id={}, page Uri={}", req.getProjectId(), req.getPageUri());
        try {
            UserContext userContext = getUser();

            PageArgConfig pageArgConfig = new PageArgConfig();
            pageArgConfig.setPageUri(req.getPageUri());
            pageArgConfig.setName(req.getName());
            pageArgConfig.setDescription(req.getDescription());

            if (req.getArgs() != null) {
                pageArgConfig.setArgs(req.getArgs().stream()
                        .map(argReq -> {
                            PageArg pageArg = new PageArg();
                            pageArg.setKey(argReq.getKey());
                            pageArg.setName(argReq.getName());
                            pageArg.setDescription(argReq.getDescription());
                            if (argReq.getDataType() != null) {
                                try {
                                    pageArg.setDataType(DataTypeEnum.valueOf(argReq.getDataType()));
                                } catch (IllegalArgumentException e) {
                                    log.warn("invalid data type: {}", argReq.getDataType());
                                    throw new IllegalArgumentException("Invalid data type: " + argReq.getDataType());
                                }
                            }
                            pageArg.setRequire(argReq.getRequire() != null ? argReq.getRequire() : false);
                            pageArg.setEnable(argReq.getEnable() != null ? argReq.getEnable() : true);
                            pageArg.setBindValue(argReq.getBindValue());
                            if (argReq.getInputType() != null) {
                                try {
                                    pageArg.setInputType(InputTypeEnum.valueOf(argReq.getInputType()));
                                } catch (IllegalArgumentException e) {
                                    log.warn("invalid input type: {}", argReq.getInputType());
                                    throw new IllegalArgumentException("Invalid input type: " + argReq.getInputType());
                                }
                            }
                            return pageArg;
                        })
                        .collect(Collectors.toList()));
            }

            var result = customPageConfigApplicationService.savePathArgs(req.getProjectId(), pageArgConfig,
                    userContext);
            if (!result.isSuccess()) {
                log.warn("[Web] project Id={},savepath failed,message={}", req.getProjectId(), result.getMessage());
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            return ReqResult.success();
        } catch (SpacePermissionException e) {
            log.error("[Web] savepath failed, project Id={}, page Uri={}, {}", req.getProjectId(), req.getPageUri(),
                    e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] savepath failed, project Id={}, page Uri={}", req.getProjectId(), req.getPageUri(), e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_CONFIG_PATH)
    @Operation(summary = "Add path config", description = "Add path config; fails if pageUri already exists")
    @PostMapping(value = "/add-path", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> addPath(@RequestBody PageArgConfigReq req) {
        log.info("[Web] add path configrequest, project Id={}, page Uri={}", req.getProjectId(), req.getPageUri());
        try {
            UserContext userContext = getUser();

            PageArgConfig pageArgConfig = new PageArgConfig();
            pageArgConfig.setPageUri(req.getPageUri());
            pageArgConfig.setName(req.getName());
            pageArgConfig.setDescription(req.getDescription());
            pageArgConfig.setArgs(new ArrayList<>());

            var result = customPageConfigApplicationService.addPath(req.getProjectId(), pageArgConfig,
                    userContext);
            if (!result.isSuccess()) {
                log.warn("[Web] project Id={},add path configfailed,message={}", req.getProjectId(), result.getMessage());
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            return ReqResult.success();
        } catch (SpacePermissionException e) {
            log.error("[Web] add path configfailed, project Id={}, page Uri={}, {}", req.getProjectId(), req.getPageUri(),
                    e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] add path configfailed, project Id={}, page Uri={}", req.getProjectId(), req.getPageUri(), e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_CONFIG_PATH)
    @Operation(summary = "Edit path config", description = "Edit path config; fails if path does not exist")
    @PostMapping(value = "/edit-path", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> editPath(@RequestBody PageArgConfigReq req) {
        log.info("[Web] editpath configrequest, project Id={}, page Uri={}", req.getProjectId(), req.getPageUri());
        try {
            UserContext userContext = getUser();

            PageArgConfig pageArgConfig = new PageArgConfig();
            pageArgConfig.setPageUri(req.getPageUri());
            pageArgConfig.setName(req.getName());
            pageArgConfig.setDescription(req.getDescription());

            var result = customPageConfigApplicationService.editPath(req.getProjectId(), pageArgConfig,
                    userContext);
            if (!result.isSuccess()) {
                log.warn("[Web] project Id={},editpath configfailed,message={}", req.getProjectId(), result.getMessage());
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            return ReqResult.success();
        } catch (SpacePermissionException e) {
            log.error("[Web] editpath configfailed, project Id={}, page Uri={}, {}", req.getProjectId(), req.getPageUri(),
                    e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] editpath configexception, project Id={}, page Uri={}", req.getProjectId(), req.getPageUri(), e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_CONFIG_PATH)
    @Operation(summary = "Delete path config", description = "Delete path config; fails if path does not exist")
    @PostMapping(value = "/delete-path", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> deletePath(@RequestBody DeletePathReq req) {
        log.info("[Web] delete path configrequest, project Id={}, page Uri={}", req.getProjectId(), req.getPageUri());
        try {
            UserContext userContext = getUser();

            var result = customPageConfigApplicationService.deletePath(req.getProjectId(), req.getPageUri(),
                    userContext);
            if (!result.isSuccess()) {
                log.warn("[Web] project Id={},delete path configfailed,message={}", req.getProjectId(), result.getMessage());
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            return ReqResult.success();
        } catch (SpacePermissionException e) {
            log.error("[Web] delete path configfailed, project Id={}, page Uri={}, {}", req.getProjectId(), req.getPageUri(),
                    e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] delete path configfailed, project Id={}, page Uri={}", req.getProjectId(), req.getPageUri(), e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_MODIFY)
    @Operation(summary = "Bind data source", description = "Bind plugin or workflow data source")
    @PostMapping(value = "/bind-data-source", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> bindDataSource(@RequestBody BindDataSourceReq req) {
        log.info("[Web] binddata sourcerequest, project Id={}, type={}, data Source Id={}",
                req.getProjectId(), req.getType(), req.getDataSourceId());
        try {
            UserContext userContext = getUser();

            var result = customPageConfigApplicationService.bindDataSource(
                    req.getProjectId(),
                    req.getType(),
                    req.getDataSourceId(),
                    userContext);
            if (!result.isSuccess()) {
                log.warn("[Web] project Id={},binddata sourcefailed,message={}", req.getProjectId(), result.getMessage());
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            return ReqResult.success();
        } catch (SpacePermissionException e) {
            log.error("[Web] binddata sourcefailed, project Id={}, type={}, data Source Id={}, {}", req.getProjectId(), req.getType(),
                    req.getDataSourceId(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] binddata sourcefailed, project Id={}, type={}, data Source Id={}",
                    req.getProjectId(), req.getType(), req.getDataSourceId(), e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_MODIFY)
    @Operation(summary = "Unbind data source", description = "Unbind a data source from the project")
    @PostMapping(value = "/unbind-data-source", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> unbindDataSource(@RequestBody BindDataSourceReq req) {
        log.info("[Web] unbinddata sourcerequest, project Id={}, type={}, data Source Id={}",
                req.getProjectId(), req.getType(), req.getDataSourceId());
        try {
            UserContext userContext = getUser();

            var result = customPageConfigApplicationService.unbindDataSource(
                    req.getProjectId(),
                    req.getType(),
                    req.getDataSourceId(),
                    userContext);
            if (!result.isSuccess()) {
                log.warn("[Web] project Id={},unbinddata sourcefailed,message={}", req.getProjectId(), result.getMessage());
                return ReqResult.create(result.getCode(), result.getMessage(), null);
            }

            return ReqResult.success();
        } catch (SpacePermissionException e) {
            log.error("[Web] unbinddata sourcefailed, project Id={}, type={}, data Source Id={}, {}", req.getProjectId(), req.getType(),
                    req.getDataSourceId(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] unbinddata sourcefailed, project Id={}, type={}, data Source Id={}",
                    req.getProjectId(), req.getType(), req.getDataSourceId(), e);
            return ReqResult.error("0001", e.getMessage());
        }
    }

}
