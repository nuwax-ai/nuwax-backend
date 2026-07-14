package com.xspaceagi.eco.market.web.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.repository.entity.ModelConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.sdk.ISkillRpcService;
import com.xspaceagi.agent.core.sdk.dto.ReqResult;
import com.xspaceagi.agent.core.spec.enums.UsageScenarioEnum;
import com.xspaceagi.agent.core.spec.utils.FileTypeUtils;
import com.xspaceagi.agent.core.spec.utils.UrlFile;
import com.xspaceagi.eco.market.domain.config.EcoMarketProperties;
import com.xspaceagi.eco.market.domain.model.EcoMarketImportRecordModel;
import com.xspaceagi.eco.market.domain.service.IEcoMarketClientSecretDomainService;
import com.xspaceagi.eco.market.sdk.model.ClientSecretDTO;
import com.xspaceagi.eco.market.spec.app.service.IEcoMarketImportApplicationService;
import com.xspaceagi.file.application.service.FileManagementService;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.infra.dao.entity.SpaceUser;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.file.InMemoryMultipartFile;
import com.xspaceagi.system.spec.utils.HttpClient;
import com.xspaceagi.system.spec.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Tag(name = "女娲生态平台相关通信接口")
@RequestMapping("/api/eco")
@RestController
public class EcoClientApiController {

    @Resource
    private IEcoMarketClientSecretDomainService ecoMarketClientSecretDomainService;

    @Resource
    private AuthService authService;

    @Resource
    private EcoMarketProperties ecoMarketProperties;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private IEcoMarketImportApplicationService iEcoMarketImportApplicationService;

    @Resource
    private ResourceGroupApplicationService resourceGroupApplicationService;

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private PluginApplicationService pluginApplicationService;

    @Resource
    private ISkillRpcService iSkillRpcService;

    @Resource
    private SkillApplicationService skillApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private FileManagementService fileManagementService;

    @Resource
    private HttpClient httpClient;

    @Value("app.version")
    private String version;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public UserDto getRequestUser(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null) {
            authorization = request.getParameter("token");
            if (authorization == null) {
                throw new BizException("Authorization information not found");
            }
        }
        String token = authorization.replace("Bearer ", "").trim();
        ClientSecretDTO clientSecretDTO = ecoMarketClientSecretDomainService.getByTenantId(RequestContext.get().getTenantId());
        Claims claims = JwtUtils.parseJwt(token, clientSecretDTO.getClientSecret());
        if (claims.getExpiration().getTime() < System.currentTimeMillis()) {
            throw new BizException("Token expired");
        }
        UserDto userDto = userApplicationService.queryById(Long.parseLong(claims.getId()));
        if (userDto == null) {
            throw new BizException("User not found");
        }
        RequestContext.get().setUser(userDto);
        RequestContext.get().setUserId(userDto.getId());
        RequestContext.get().setLangMap(userDto.getLangMap());
        return (UserDto) RequestContext.get().getUser();
    }

    //版本获取
    @Operation(summary = "获取版本信息", description = "JSONP接口，需传callback参数")
    @ApiResponse(responseCode = "200", description = "成功",
            content = @Content(mediaType = "application/javascript",
                    schema = @Schema(implementation = VersionResponse.class)))
    @GetMapping("/client/version")
    public void getVersion(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String callback = request.getParameter("callback");
        ReqResult<String> result;
        try {
            result = ReqResult.success(version);
        } catch (Exception e) {
            result = ReqResult.create("4030", e.getMessage(), null);
        }
        response.setContentType("application/javascript;charset=UTF-8");
        String json = objectMapper.writeValueAsString(result);
        response.getWriter().write(callback + "(" + json + ");");
    }

    @PostMapping("/client/version0")
    public ReqResult<String> getVersion0() {
        ReqResult<String> result;
        try {
            result = ReqResult.success(version);
        } catch (Exception e) {
            result = ReqResult.create("4030", e.getMessage(), null);
        }
        return result;
    }

    @Operation(summary = "获取用户空间列表", description = "JSONP接口，需传callback参数")
    @ApiResponse(responseCode = "200", description = "成功",
            content = @Content(mediaType = "application/javascript",
                    schema = @Schema(implementation = SpaceListResponse.class)))
    @GetMapping("/client/user/space/list")
    public void getUserSpaceList(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String callback = request.getParameter("callback");
        ReqResult<List<SpaceDto>> result;
        try {
            UserDto userDto = getRequestUser(request);
            List<SpaceDto> spaces = spaceApplicationService.queryListByUserId(userDto.getId());
            result = ReqResult.success(spaces);
        } catch (Exception e) {
            result = ReqResult.create("4030", e.getMessage(), null);
        }
        response.setContentType("application/javascript;charset=UTF-8");
        String json = objectMapper.writeValueAsString(result);
        response.getWriter().write(callback + "(" + json + ");");
    }


    @PostMapping("/client/user/space/list0")
    public ReqResult<List<SpaceDto>> getUserSpaceList0() {
        ReqResult<List<SpaceDto>> result;
        try {
            if (!RequestContext.get().isLogin()) {
                return ReqResult.error("Login required");
            }
            UserDto userDto = (UserDto) RequestContext.get().getUser();
            List<SpaceDto> spaces = spaceApplicationService.queryListByUserId(userDto.getId());
            result = ReqResult.success(spaces);
        } catch (Exception e) {
            result = ReqResult.create("4030", e.getMessage(), null);
        }
        return result;
    }


    @Operation(summary = "根据生态对象查询空间列表（含导入状态）", description = "JSONP接口，需传callback参数")
    @ApiResponse(responseCode = "200", description = "成功",
            content = @Content(mediaType = "application/javascript",
                    schema = @Schema(implementation = SpaceImportStatusListResponse.class)))
    @GetMapping("/client/space/import/status")
    public void getSpaceImportStatus(HttpServletRequest request, HttpServletResponse response,
                                     @RequestParam String targetType,
                                     @RequestParam String ecoTargetId) throws IOException {
        String callback = request.getParameter("callback");
        UserDto userDto = getRequestUser(request);
        ReqResult<List<SpaceImportStatus>> result = getImportStatus(userDto, targetType, ecoTargetId);
        response.setContentType("application/javascript;charset=UTF-8");
        String json = objectMapper.writeValueAsString(result);
        response.getWriter().write(callback + "(" + json + ");");
    }

    @PostMapping("/client/space/import/status0")
    public ReqResult<List<SpaceImportStatus>> getSpaceImportStatus0(@RequestBody Map<String, String> params) {
        if (!RequestContext.get().isLogin()) {
            return ReqResult.error("Login required");
        }
        String targetType = params.get("targetType");
        String ecoTargetId = params.get("ecoTargetId");
        UserDto userDto = (UserDto) RequestContext.get().getUser();
        return getImportStatus(userDto, targetType, ecoTargetId);
    }

    private ReqResult<List<SpaceImportStatus>> getImportStatus(UserDto userDto, String targetType, String ecoTargetId) {
        ReqResult<List<SpaceImportStatus>> result;
        try {
            List<SpaceDto> spaces = spaceApplicationService.queryListByUserId(userDto.getId());
            targetType = targetType.equals("Tool") ? "Plugin" : targetType;
            // 查询该用户针对此生态对象已导入的空间记录
            var importRecords = iEcoMarketImportApplicationService.listImportRecordsByEcoTargetId(
                    userDto.getId(), targetType, ecoTargetId);
            Set<Long> importedSpaceIds = importRecords.stream()
                    .map(EcoMarketImportRecordModel::getSpaceId)
                    .collect(Collectors.toSet());
            if ((targetType.equals("Plugin") || targetType.equals("Skill")) && !importRecords.isEmpty()) {
                PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.valueOf(targetType), importRecords.get(0).getTargetId());
                if (publishedDto != null && publishedDto.getPublishedSpaceIds() != null) {
                    importedSpaceIds.addAll(publishedDto.getPublishedSpaceIds());
                }
            }
            List<SpaceImportStatus> statusList = new ArrayList<>();
            for (SpaceDto space : spaces) {
                // 过滤：普通用户且空间未开启开发功能时跳过
                if (space.getCurrentUserRole() == SpaceUser.Role.User
                        && (space.getAllowDevelop() == null || space.getAllowDevelop() == 0)) {
                    continue;
                }
                SpaceImportStatus status = SpaceImportStatus.of(space, importedSpaceIds.contains(space.getId()));
                statusList.add(status);
            }
            result = ReqResult.success(statusList);
        } catch (Exception e) {
            result = ReqResult.create("4030", e.getMessage(), null);
        }
        return result;
    }

    @Operation(summary = "导入配置", description = "JSONP接口，从生态市场导入配置到指定空间")
    @ApiResponse(responseCode = "200", description = "成功",
            content = @Content(mediaType = "application/javascript",
                    schema = @Schema(implementation = ImportResponse.class)))
    @GetMapping("/client/import/config")
    public void importConfig(HttpServletRequest request, HttpServletResponse response,
                             @RequestParam String importDataKey) throws IOException {
        String callback = request.getParameter("callback");
        UserDto userDto = getRequestUser(request);
        ReqResult<Map<String, Object>> result = importConfigs(userDto, importDataKey);
        response.setContentType("application/javascript;charset=UTF-8");
        String json = objectMapper.writeValueAsString(result);
        response.getWriter().write(callback + "(" + json + ");");
    }

    @PostMapping("/client/import/config0")
    public ReqResult<Map<String, Object>> importConfig0(@RequestBody Map<String, String> params) {
        if (!RequestContext.get().isLogin()) {
            return ReqResult.error("Login required");
        }
        String importDataKey = params.get("importDataKey");
        UserDto userDto = (UserDto) RequestContext.get().getUser();
        return importConfigs(userDto, importDataKey);
    }

    private ReqResult<Map<String, Object>> importConfigs(UserDto userDto, String importDataKey) {
        ReqResult<Map<String, Object>> result;
        try {
            TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
            tenantConfigDto.setSiteUrl(tenantConfigDto.getSiteConfigUrl());
            RequestContext.get().setUserContext(UserContext.builder().userId(userDto.getId()).tenantId(userDto.getTenantId()).userName(userDto.getUserName()).build());
            String content = httpClient.get(ecoMarketProperties.getWeb().getBaseUrl() + "/api/eco/server/getImportData?dataKey=" + importDataKey);
            JSONObject importDataResponse = JSONObject.parseObject(content);
            if (!ReqResult.SUCCESS.equals(importDataResponse.getString("code"))) {
                result = ReqResult.create(importDataResponse.getString("code"), importDataResponse.getString("message"), null);
            } else {
                JSONObject importData = importDataResponse.getJSONObject("data");
                String targetType = importData.getString("targetType");
                String ecoTargetId = importData.getString("targetId");
                List<Long> spaceIds = importData.getList("spaceIds", Long.class);
                String targetData = importData.getString("targetData");
                Assert.notEmpty(spaceIds, "spaceIds不能为空");
                Assert.notNull(targetData, "targetData不能为空");
                Assert.notNull(targetType, "targetType不能为空");
                Assert.notNull(ecoTargetId, "targetId不能为空");
                targetType = targetType.equals("Tool") ? "Plugin" : targetType;
                List<SpaceDto> userSpaces = spaceApplicationService.queryListByUserId(userDto.getId());
                if (targetType.equals("Model")) {
                    for (Long spaceId : spaceIds) {
                        if (spaceId != -1L) {
                            SpaceDto spaceDto = userSpaces.stream()
                                    .filter(space -> spaceIds.contains(space.getId()))
                                    .findFirst()
                                    .orElse(null);
                            if (spaceDto == null) {
                                continue;
                            }
                        }
                        if (spaceId == -1L && userDto.getRole() != User.Role.Admin) {
                            continue;
                        }
                        ModelConfigDto modelConfigDto = JSON.parseObject(targetData, ModelConfigDto.class);
                        modelConfigDto.setSpaceId(spaceId);
                        modelConfigDto.setScope(spaceId == -1L ? ModelConfig.ModelScopeEnum.Tenant : ModelConfig.ModelScopeEnum.Space);
                        EcoMarketImportRecordModel imported = iEcoMarketImportApplicationService.existsImportRecord(spaceId, targetType, ecoTargetId);
                        if (imported != null) {
                            if (modelApplicationService.queryModelConfigById(imported.getTargetId()) != null) {
                                modelConfigDto.setId(imported.getTargetId());
                            } else {
                                iEcoMarketImportApplicationService.deleteImportRecord(imported.getId());
                                imported = null;
                            }
                        }
                        modelApplicationService.addOrUpdate(modelConfigDto);
                        if (imported == null) {
                            // 记录导入
                            EcoMarketImportRecordModel record = EcoMarketImportRecordModel.builder()
                                    .tenantId(RequestContext.get().getTenantId())
                                    .userId(userDto.getId())
                                    .spaceId(spaceId)
                                    .targetType(targetType)
                                    .targetId(modelConfigDto.getId()) // 真实导入逻辑完成后会填充此字段
                                    .ecoTargetId(ecoTargetId)
                                    .build();
                            iEcoMarketImportApplicationService.addImportRecord(record);
                        }
                    }

                }

                SpaceDto spaceDto = userSpaces.stream().filter(space -> space.getType() == Space.Type.Personal).findFirst().orElse(null);
                if (spaceDto == null) {
                    throw new BizException("The user lacks personal space.");
                }

                if (targetType.equals("Plugin")) {
                    JSONArray objects = JSON.parseArray(targetData);
                    String groupInfo = importData.getString("groupInfo");
                    Long groupId = null;
                    if (groupInfo != null) {
                        ResourceGroupDto ecoResourceGroupDto = JSON.parseObject(importData.getString("groupInfo"), ResourceGroupDto.class);
                        ResourceGroupDto resourceGroupDto = resourceGroupApplicationService.queryByName(spaceDto.getId(), "Plugin", ecoResourceGroupDto.getName());
                        if (resourceGroupDto == null) {
                            resourceGroupDto = new ResourceGroupDto();
                            resourceGroupDto.setName(ecoResourceGroupDto.getName());
                            resourceGroupDto.setDescription(ecoResourceGroupDto.getDescription());
                            resourceGroupDto.setIcon(fileExchange(ecoResourceGroupDto.getIcon()));
                            resourceGroupDto.setType("Plugin");
                            resourceGroupDto.setSpaceId(spaceDto.getId());
                            groupId = resourceGroupApplicationService.add(resourceGroupDto);
                        } else {
                            groupId = resourceGroupDto.getId();
                        }
                    }
                    for (int i = 0; i < objects.size(); i++) {
                        PluginDto pluginDto = objects.getObject(i, PluginDto.class);
                        ecoTargetId = pluginDto.getToolId();
                        EcoMarketImportRecordModel imported = iEcoMarketImportApplicationService.existsImportRecord(spaceDto.getId(), "Plugin", ecoTargetId);
                        Long pluginId = imported == null ? null : imported.getTargetId();
                        if (pluginId != null) {
                            PluginDto pluginDto1 = pluginApplicationService.queryById(pluginId);
                            if (pluginDto1 == null) {
                                pluginId = null;
                                iEcoMarketImportApplicationService.deleteImportRecord(imported.getId());
                                imported = null;
                            }
                        }
                        if (pluginId == null) {
                            PluginAddDto pluginAddDto = new PluginAddDto();
                            pluginAddDto.setCreatorId(userDto.getId());
                            pluginAddDto.setSpaceId(spaceDto.getId());
                            pluginAddDto.setName(pluginDto.getName());
                            pluginAddDto.setDescription(pluginDto.getDescription());
                            pluginAddDto.setType(pluginDto.getType());
                            pluginAddDto.setCodeLang(pluginDto.getCodeLang());
                            pluginAddDto.setIcon(fileExchange(pluginDto.getIcon()));
                            pluginId = pluginApplicationService.add(pluginAddDto);
                        }
                        if (groupId != null) {
                            resourceGroupApplicationService.addResourceToGroup(groupId, "Plugin", pluginId);
                        }
                        PluginUpdateDto<Object> pluginUpdateDto = new PluginUpdateDto<>();
                        pluginUpdateDto.setId(pluginId);
                        pluginUpdateDto.setName(pluginDto.getName());
                        pluginUpdateDto.setConfig(pluginDto.getConfig());
                        pluginUpdateDto.setDescription(pluginDto.getDescription());
                        pluginUpdateDto.setIcon(fileExchange(pluginDto.getIcon()));
                        pluginApplicationService.update(pluginUpdateDto);
                        pluginDto.setId(pluginId);
                        pluginDto.setSpaceId(spaceDto.getId());
                        pluginDto.setCreatorId(userDto.getId());
                        if (imported == null) {
                            // 记录导入
                            EcoMarketImportRecordModel record = EcoMarketImportRecordModel.builder()
                                    .tenantId(RequestContext.get().getTenantId())
                                    .userId(userDto.getId())
                                    .spaceId(spaceDto.getId())
                                    .targetType(targetType)
                                    .targetId(pluginId) // 真实导入逻辑完成后会填充此字段
                                    .ecoTargetId(ecoTargetId)
                                    .build();
                            iEcoMarketImportApplicationService.addImportRecord(record);
                        }
                        PublishApplySubmitDto publishApplySubmitDto = new PublishApplySubmitDto();
                        publishApplySubmitDto.setCategory(pluginDto.getCategory() == null ? "Other" : pluginDto.getCategory());
                        publishApplySubmitDto.setTargetId(pluginId);
                        publishApplySubmitDto.setTargetType(Published.TargetType.Plugin);
                        publishApplySubmitDto.setRemark("From the Nuwax ecosystem platform");
                        List<PublishApplySubmitDto.PublishItem> items = new ArrayList<>();
                        publishApplySubmitDto.setItems(items);
                        for (Long spaceId : spaceIds) {
                            PublishApplySubmitDto.PublishItem item = new PublishApplySubmitDto.PublishItem();
                            item.setScope(spaceId == -1L ? Published.PublishScope.Tenant : Published.PublishScope.Space);
                            item.setSpaceId(spaceId);
                            item.setAllowCopy(0);
                            item.setOnlyTemplate(0);
                            items.add(item);
                        }
                        publishApplicationService.publishOrApply(publishApplySubmitDto);
                    }

                }

                if (targetType.equals("Skill")) {
                    SkillConfigDto skillConfigDto = JSON.parseObject(targetData, SkillConfigDto.class);
                    EcoMarketImportRecordModel imported = iEcoMarketImportApplicationService.existsImportRecord(spaceDto.getId(), targetType, ecoTargetId);
                    if (imported != null) {
                        if (skillApplicationService.queryById(imported.getTargetId()) != null) {
                            skillConfigDto.setId(imported.getTargetId());
                        } else {
                            iEcoMarketImportApplicationService.deleteImportRecord(imported.getId());
                            imported = null;
                        }
                    }
                    log.info("导入技能：{}, targetId {}", skillConfigDto, imported != null ? imported.getTargetId() : null);
                    Long skillId = iSkillRpcService.importSkill(skillConfigDto.getZipFileUrl(), null, imported != null ? imported.getTargetId() : null, spaceDto.getId(), List.of(UsageScenarioEnum.TaskAgent));
                    SkillConfigDto updateSkillConfigDto = new SkillConfigDto();
                    updateSkillConfigDto.setId(skillId);
                    updateSkillConfigDto.setIcon(fileExchange(skillConfigDto.getIcon()));
                    updateSkillConfigDto.setDescription(skillConfigDto.getDescription());
                    updateSkillConfigDto.setName(skillConfigDto.getName());
                    skillApplicationService.update(updateSkillConfigDto, false);
                    if (imported == null) {
                        // 记录导入
                        EcoMarketImportRecordModel record = EcoMarketImportRecordModel.builder()
                                .tenantId(RequestContext.get().getTenantId())
                                .userId(userDto.getId())
                                .spaceId(spaceDto.getId())
                                .targetType(targetType)
                                .targetId(skillId) // 真实导入逻辑完成后会填充此字段
                                .ecoTargetId(ecoTargetId)
                                .build();
                        iEcoMarketImportApplicationService.addImportRecord(record);
                    }
                    PublishApplySubmitDto publishApplySubmitDto = new PublishApplySubmitDto();
                    publishApplySubmitDto.setCategory(skillConfigDto.getCategory() == null ? "Other" : skillConfigDto.getCategory());
                    publishApplySubmitDto.setTargetId(skillId);
                    publishApplySubmitDto.setTargetType(Published.TargetType.Skill);
                    publishApplySubmitDto.setRemark("From the Nuwax ecosystem platform");
                    List<PublishApplySubmitDto.PublishItem> items = new ArrayList<>();
                    publishApplySubmitDto.setItems(items);
                    for (Long spaceId : spaceIds) {
                        PublishApplySubmitDto.PublishItem item = new PublishApplySubmitDto.PublishItem();
                        item.setScope(spaceId == -1L ? Published.PublishScope.Tenant : Published.PublishScope.Space);
                        item.setSpaceId(spaceId);
                        item.setAllowCopy(0);
                        item.setOnlyTemplate(0);
                        items.add(item);
                    }
                    publishApplicationService.publishOrApply(publishApplySubmitDto);
                }

                result = ReqResult.success();
            }
        } catch (Exception e) {
            log.error("导入失败", e);
            result = ReqResult.create("4030", e.getMessage(), null);
        }

        return result;
    }

    @Operation(summary = "跳转到生态市场", description = "跳转生态市场")
    @GetMapping("/redirect")
    public void redirect(HttpServletResponse response) {
        ClientSecretDTO clientSecretDTO = ecoMarketClientSecretDomainService.getByTenantId(RequestContext.get().getTenantId());
        if (clientSecretDTO == null) {
            // 未找到客户端信息 翻译成英文
            throw new RuntimeException("未找到客户端信息");
        }
        UserDto user = (UserDto) RequestContext.get().getUser();
        String token = authService.newEcoToken(clientSecretDTO.getClientId(), clientSecretDTO.getClientSecret(), user);
        try {
            log.info("跳转到生态市场：{}", token);
            response.sendRedirect(ecoMarketProperties.getWeb().getBaseUrl() + "/api/eco/server?token=" + token);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Schema(description = "用户空间列表响应")
    private static class SpaceListResponse extends ReqResult<List<SpaceDto>> {
    }

    @Schema(description = "版本信息响应")
    private static class VersionResponse extends ReqResult<String> {
    }

    @Schema(description = "空间导入状态")
    private static class SpaceImportStatus {
        @Schema(description = "空间ID")
        private Long spaceId;
        @Schema(description = "空间名称")
        private String spaceName;
        @Schema(description = "空间类型")
        private String type;
        @Schema(description = "当前用户角色")
        private String userRole;
        @Schema(description = "是否已导入")
        private Boolean imported;

        static SpaceImportStatus of(SpaceDto space, boolean imported) {
            SpaceImportStatus s = new SpaceImportStatus();
            s.spaceId = space.getId();
            s.spaceName = space.getName();
            s.type = space.getType() != null ? space.getType().name() : null;
            s.userRole = space.getCurrentUserRole() != null ? space.getCurrentUserRole().name() : null;
            s.imported = imported;
            return s;
        }

        public Long getSpaceId() {
            return spaceId;
        }

        public String getSpaceName() {
            return spaceName;
        }

        public String getType() {
            return type;
        }

        public String getUserRole() {
            return userRole;
        }

        public Boolean getImported() {
            return imported;
        }
    }

    @Schema(description = "空间导入状态列表响应")
    private static class SpaceImportStatusListResponse extends ReqResult<List<SpaceImportStatus>> {
    }

    @Schema(description = "导入配置响应")
    private static class ImportResponse extends ReqResult<Map<String, Object>> {
    }

    private String fileExchange(String url) {
        try {
            byte[] bytes = UrlFile.downLoad(url);
            String contentType = FileTypeUtils.getContentTypeByFileName(url).toString();
            InMemoryMultipartFile multipartFile = new InMemoryMultipartFile("file", "", contentType, bytes);
            Long tenantId = RequestContext.get() != null ? RequestContext.get().getTenantId() : null;
            Long userId = RequestContext.get() != null ? RequestContext.get().getUserId() : null;
            return fileManagementService.uploadFile(multipartFile, tenantId, userId, "skill_dev", null, null, true).getFileUrl();
        } catch (Exception e) {
            log.warn("fileExchange error {}", url, e);
            return null;
        }
    }
}
