package com.xspaceagi.system.web.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.system.application.constant.SupportedLocaleConstants;
import com.xspaceagi.system.application.dto.*;
import com.xspaceagi.system.application.constant.I18nLangTagConstraints;
import com.xspaceagi.system.application.dto.permission.export.I18nConfigExportDto;
import com.xspaceagi.system.application.service.I18nApplicationService;
import com.xspaceagi.system.application.service.I18nConfigDiffService;
import com.xspaceagi.system.application.service.I18nExportService;
import com.xspaceagi.system.application.service.I18nImportService;
import com.xspaceagi.system.application.service.I18nLangApplicationService;
import com.xspaceagi.system.infra.dao.entity.Tenant;
import com.xspaceagi.system.infra.dao.service.TenantService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.annotation.SaasAdmin;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.constants.I18nSyncConstants;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.I18nSideEnum;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

/**
 * 国际化管理器
 */
@Slf4j
@Tag(name = "多语言管理", description = "国际化相关接口（语言管理、多语言配置）")
@RestController
@RequestMapping("/api/system/i18n")
public class I18nManageController {

    @Resource
    private I18nLangApplicationService i18nLangApplicationService;

    @Resource
    private I18nApplicationService i18nApplicationService;

    @Resource
    private I18nExportService i18nExportService;

    @Resource
    private I18nImportService i18nImportService;

    @Resource
    private I18nConfigDiffService i18nConfigDiffService;

    @Resource
    private TenantService tenantService;

    private final long TRANSLATE_BATCH_SIZE = 50L;

    // ==================== I18nLang 相关接口 ====================

    @Operation(summary = "可选语言列表（用于前端初始化；与 Accept-Language 匹配项排在第 1 位）")
    @GetMapping(value = "/locale/bootstrap", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<List<SupportedLocaleOptionDto>> localeBootstrap(HttpServletRequest request) {
        List<SupportedLocaleOptionDto> options = SupportedLocaleConstants.DEFAULT_LOCALE_TAG_WHITELIST.stream()
                .map(SupportedLocaleOptionDto::fromTag)
                .collect(Collectors.toList());
        List<Locale> supportedLocales = SupportedLocaleConstants.DEFAULT_LOCALE_TAG_WHITELIST.stream()
                .map(Locale::forLanguageTag)
                .toList();

        String acceptLanguage = request.getHeader("Accept-Language");
        if (StringUtils.isBlank(acceptLanguage)) {
            return ReqResult.success(options);
        }
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguage);
            Locale matched = Locale.lookup(ranges, supportedLocales);
            if (matched == null) {
                return ReqResult.success(options);
            }
            String matchedTag = matched.toLanguageTag();
            Optional<SupportedLocaleOptionDto> hit = options.stream()
                    .filter(o -> matchedTag.equalsIgnoreCase(o.getTag()))
                    .findFirst();
            if (hit.isEmpty()) {
                return ReqResult.success(options);
            }
            List<SupportedLocaleOptionDto> reordered = new ArrayList<>(options.size());
            reordered.add(hit.get());
            options.stream()
                    .filter(o -> !matchedTag.equalsIgnoreCase(o.getTag()))
                    .forEach(reordered::add);
            return ReqResult.success(reordered);
        } catch (IllegalArgumentException e) {
            log.debug("Accept-Language 解析失败: {}", acceptLanguage, e);
            return ReqResult.success(options);
        }
    }

    /**
     * 新增语言
     */
    @RequireResource(I18N_LANG_ADD)
    @Operation(summary = "新增语言")
    @PostMapping(value = "/lang/add", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Long> addLang(@RequestBody @Valid I18nLangAddDto addDto) {
        log.info("[addLang] 新增语言，name={}, lang={}", addDto.getName(), addDto.getLang());
        Assert.notNull(addDto.getName(), "Parameter 'name' cannot be left blank.");
        Assert.notNull(addDto.getLang(), "Parameter 'lang' cannot be left blank.");
        Long id = i18nLangApplicationService.add(addDto);
        return ReqResult.success(id);
    }

    /**
     * 删除语言
     */
    @RequireResource(I18N_LANG_DELETE)
    @Operation(summary = "删除语言")
    @PostMapping(value = "/lang/delete/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> deleteLang(@PathVariable("id") Long id) {
        log.info("[deleteLang] 删除语言，id={}", id);
        Assert.notNull(id, "Parameter 'id' cannot be left blank.");
        i18nLangApplicationService.delete(id);
        return ReqResult.success();
    }

    /**
     * 更新语言
     */
    @RequireResource(I18N_LANG_MODIFY)
    @Operation(summary = "更新语言")
    @PostMapping(value = "/lang/update", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> updateLang(@RequestBody @Valid I18nLangUpdateDto updateDto) {
        log.info("[updateLang] 更新语言，id={}, name={}, status={}",
                updateDto.getId(), updateDto.getName(), updateDto.getStatus());
        Assert.notNull(updateDto.getId(), "Parameter 'id' cannot be left blank.");
        i18nLangApplicationService.update(updateDto);
        return ReqResult.success();
    }

    /**
     * 设置为默认语言
     */
    @RequireResource(I18N_LANG_MODIFY)
    @Operation(summary = "设置为默认语言")
    @PostMapping(value = "/lang/setDefault/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> setDefaultLang(@PathVariable("id") Long id) {
        log.info("[setDefaultLang] 设置为默认语言，id={}", id);
        i18nLangApplicationService.setDefault(id);
        return ReqResult.success();
    }

    /**
     * 查询全部语言
     */
    @RequireResource(I18N_LANG_QUERY)
    @Operation(summary = "查询全部语言")
    @GetMapping(value = "/lang/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<List<I18nLangDto>> queryAllLang() {
        log.info("[queryAllLang] 查询全部语言");
        List<I18nLangDto> list = i18nLangApplicationService.queryAll();
        return ReqResult.success(list);
    }

    /**
     * 批量更新语言排序
     */
    @RequireResource(I18N_LANG_MODIFY)
    @Operation(summary = "批量更新语言排序")
    @PostMapping(value = "/lang/sort", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> updateLangSort(@RequestBody @Valid List<I18nLangDto> sortList) {
        log.info("[updateLangSort] 批量更新语言排序，size={}", sortList.size());
        i18nLangApplicationService.updateSort(sortList);
        return ReqResult.success();
    }

    // ==================== I18nConfig 相关接口 ====================

    @RequireResource(I18N_LANG_QUERY)
    @Operation(summary = "查询多语言端列表")
    @GetMapping(value = "/side/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<List<String>> moduleList() {
        return ReqResult.success(Arrays.stream(I18nSideEnum.values()).map(I18nSideEnum::getSide).toList());
    }


    /**
     * 分页查询多语言配置（条件：side、lang、module 精确匹配，key 对 fieldKey 模糊匹配）
     */
    @RequireResource(I18N_LANG_QUERY)
    @Operation(summary = "分页查询多语言配置列表")
    @PostMapping(value = "/config/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<IPage<I18nConfigDto>> queryI18nConfigList(@RequestBody(required = false) I18nConfigQueryDto query) {
        if (query == null) {
            query = new I18nConfigQueryDto();
        }
        log.info("[queryI18nConfigList] 查询多语言配置, query={}", query);
        IPage<I18nConfigDto> page = i18nApplicationService.queryI18nConfigPage(RequestContext.get().getTenantId(), query);
        return ReqResult.success(page);
    }

    /**
     * 条件与 {@link #queryI18nConfigList} 一致，忽略分页字段，全量导出为 JSON 文件（结构与内置 i18n-config 单行一致）。
     */
    @RequireResource(I18N_LANG_QUERY)
    @Operation(summary = "按条件导出多语言配置为 JSON 文件")
    @PostMapping(value = "/config/export", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public byte[] exportI18nConfig(@RequestBody(required = false) I18nConfigQueryDto query,
                                   HttpServletResponse response) {
        if (query == null) {
            query = new I18nConfigQueryDto();
        }
        log.info("[exportI18nConfig] 按条件导出多语言配置, query={}", query);
        I18nConfigQueryExportDto data = i18nApplicationService.exportI18nConfig(RequestContext.get().getTenantId(), query);
        String json = JsonSerializeUtil.toJSONString(data);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String lang = "";
        if (StringUtils.isNotBlank(query.getLang())) {
            lang = I18nLangTagConstraints.tryNormalizeToStoredForm(query.getLang().trim()).orElse(query.getLang().trim());
        }
        String fileName = "i18n-config-" + lang + ".json";
        response.setHeader("Content-Disposition",
                "attachment;filename=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        return body;
    }

//    @RequireResource(I18N_LANG_TRANSLATE)
//    @Operation(summary = "翻译全部配置（不为空的不会翻译）")
//    @GetMapping(value = "/config/translateAll", produces = MediaType.APPLICATION_JSON_VALUE)
//    public Flux<ReqResult<I18nConfigDto>> translateI18nConfigList(@RequestParam(value = "lang", required = false) String lang) {
//        List<I18nConfigDto> list = i18nApplicationService.queryI18nConfigList(RequestContext.get().getTenantId(), "System", null, null, null, lang);
//        Flux<ReqResult<I18nConfigDto>> flux = Flux.create(listFluxSink -> {
//            list.forEach(item -> {
//                if (StringUtils.isNotBlank(item.getValue())) {
//                    return;
//                }
//                try {
//                    listFluxSink.next(ReqResult.success(i18nApplicationService.translateMessage(item)));
//                } catch (Exception e) {
//                    log.warn("翻译异常", e);
//                    listFluxSink.next(ReqResult.error(I18nMessage.ALERT_MSG_098.getKey()));
//                }
//            });
//            listFluxSink.complete();
//        });
//        return flux.publishOn(Schedulers.boundedElastic());
//    }

//    @RequireResource(I18N_LANG_TRANSLATE)
//    @Operation(summary = "翻译指定配置（会覆盖原有的）")
//    @PostMapping(value = "/config/translate", produces = MediaType.APPLICATION_JSON_VALUE)
//    public ReqResult<I18nConfigDto> translateI18nConfig(@RequestBody I18nConfigDto i18nConfigDto) {
//        return ReqResult.success(i18nApplicationService.translateMessage(i18nConfigDto));
//    }

    @RequireResource(I18N_LANG_TRANSLATE)
    @Operation(summary = "翻译单个key")
    @PostMapping(value = "/config/translateKey", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> translateForKey(@RequestBody I18nConfigDto i18nConfigDto,
                                           @RequestParam("sourceLang") String sourceLang,
                                           @RequestParam("targetLang") String targetLang) {
        Assert.hasText(sourceLang, "The source language cannot be empty.");
        Assert.hasText(targetLang, "The target language cannot be empty.");
        String sourceCanon = I18nLangTagConstraints.tryNormalizeToStoredForm(sourceLang)
                .orElseThrow(() -> new IllegalArgumentException(I18nLangTagConstraints.LANG_TAG_MESSAGE_EN));
        String targetCanon = I18nLangTagConstraints.tryNormalizeToStoredForm(targetLang)
                .orElseThrow(() -> new IllegalArgumentException(I18nLangTagConstraints.LANG_TAG_MESSAGE_EN));
        Assert.isTrue(!I18nLangTagConstraints.sameLanguageTag(sourceCanon, targetCanon),
                "The source language and target language cannot be the same.");

        Long tenantId = RequestContext.get().getTenantId();
        i18nApplicationService.translateForKey(tenantId, i18nConfigDto, sourceCanon, targetCanon);
        return ReqResult.success();
    }

    @RequireResource(I18N_LANG_TRANSLATE)
    @Operation(summary = "批量翻译指定 key 列表")
    @PostMapping(value = "/config/translateKeys", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> translateForKeys(@RequestBody @Valid I18nBatchTranslateDto request) {
        String sourceCanon = I18nLangTagConstraints.tryNormalizeToStoredForm(request.getSourceLang())
                .orElseThrow(() -> new IllegalArgumentException(I18nLangTagConstraints.LANG_TAG_MESSAGE_EN));
        String targetCanon = I18nLangTagConstraints.tryNormalizeToStoredForm(request.getTargetLang())
                .orElseThrow(() -> new IllegalArgumentException(I18nLangTagConstraints.LANG_TAG_MESSAGE_EN));
        Assert.isTrue(!I18nLangTagConstraints.sameLanguageTag(sourceCanon, targetCanon),
                "The source language and target language cannot be the same.");

        Long tenantId = RequestContext.get().getTenantId();
        i18nApplicationService.translateForKeys(tenantId, request.getKeys(), sourceCanon, targetCanon);
        return ReqResult.success();
    }

    @RequireResource(I18N_LANG_TRANSLATE)
    @Operation(summary = "翻译所有key")
    @PostMapping(value = "/config/translateAll", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ReqResult<Map<String, Object>>>> translateForAll(@RequestParam("sourceLang") String sourceLang, @RequestParam("targetLang") String targetLang) {
        Assert.hasText(sourceLang, "The source language cannot be empty.");
        Assert.hasText(targetLang, "The target language cannot be empty.");
        String sourceCanon = I18nLangTagConstraints.tryNormalizeToStoredForm(sourceLang)
                .orElseThrow(() -> new IllegalArgumentException(I18nLangTagConstraints.LANG_TAG_MESSAGE_EN));
        String targetCanon = I18nLangTagConstraints.tryNormalizeToStoredForm(targetLang)
                .orElseThrow(() -> new IllegalArgumentException(I18nLangTagConstraints.LANG_TAG_MESSAGE_EN));
        Assert.isTrue(!I18nLangTagConstraints.sameLanguageTag(sourceCanon, targetCanon),
                "The source language and target language cannot be the same.");
        Long tenantId = RequestContext.get().getTenantId();
        List<I18nConfigDto> sourceAll = i18nApplicationService.queryI18nConfigList(tenantId, "System", null, null, null, sourceCanon);
        Map<String, I18nConfigDto> sourceMap = sourceAll.stream()
                .filter(item -> StringUtils.isNotBlank(item.getKey()) && StringUtils.isNotBlank(item.getValue()))
                .collect(Collectors.toMap(I18nConfigDto::getKey, item -> item, (a, b) -> a, LinkedHashMap::new));
        List<I18nConfigDto> targetAll = i18nApplicationService.queryI18nConfigList(tenantId, "System", null, null, null, targetCanon);
        List<I18nConfigDto> blankTargetList = targetAll.stream()
                .filter(item -> StringUtils.isBlank(item.getValue()))
                .toList();
        long total = blankTargetList.size();
        int totalPages = total == 0 ? 0 : (int) ((total + TRANSLATE_BATCH_SIZE - 1) / TRANSLATE_BATCH_SIZE);
        List<Map<String, Object>> failedItems = Collections.synchronizedList(new ArrayList<>());

        Flux<ServerSentEvent<ReqResult<Map<String, Object>>>> progressStream = Flux
                .range(1, totalPages)
                .concatMap(pageNo -> Mono.fromCallable(() -> {
                            RequestContext.setThreadTenantId(tenantId);
                            try {
                                int fromIndex = Math.toIntExact((long) (pageNo - 1) * TRANSLATE_BATCH_SIZE);
                                int toIndex = Math.min(fromIndex + Math.toIntExact(TRANSLATE_BATCH_SIZE), blankTargetList.size());
                                List<I18nConfigDto> targetChunk = blankTargetList.subList(fromIndex, toIndex);
                                List<I18nConfigDto> sourceChunk = targetChunk.stream()
                                        .map(item -> sourceMap.get(item.getKey()))
                                        .filter(Objects::nonNull)
                                        .toList();
                                try {
                                    if (!sourceChunk.isEmpty()) {
                                        i18nApplicationService.translateForKeysBatch(tenantId, sourceChunk, sourceCanon, targetCanon);
                                    }
                                } catch (Exception e) {
                                    log.warn("translateAll 单页翻译失败, pageNo={}, sourceLang={}, targetLang={}",
                                            pageNo, sourceCanon, targetCanon, e);
                                    Map<String, Object> failed = new HashMap<>();
                                    failed.put("pageNo", pageNo);
                                    failed.put("error", e.getMessage());
                                    failed.put("count", targetChunk.size());
                                    failed.put("keys", targetChunk.stream().map(I18nConfigDto::getKey).toList());
                                    failedItems.add(failed);
                                }
                                long completed = Math.min((long) pageNo * TRANSLATE_BATCH_SIZE, total);
                                Map<String, Object> payload = new java.util.HashMap<>();
                                payload.put("phase", "progress");
                                payload.put("total", total);
                                payload.put("completed", completed);
                                payload.put("percent", total == 0 ? 100 : (int) (completed * 100 / total));
                                return ServerSentEvent.<ReqResult<Map<String, Object>>>builder()
                                        .event("progress")
                                        .data(ReqResult.success(payload))
                                        .build();
                            } catch (Exception e) {
                                log.warn("translateAll 单页处理失败, pageNo={}, sourceLang={}, targetLang={}",
                                        pageNo, sourceCanon, targetCanon, e);
                                Map<String, Object> failed = new HashMap<>();
                                failed.put("pageNo", pageNo);
                                failed.put("error", e.getMessage());
                                failed.put("count", 0);
                                failed.put("keys", List.of());
                                failedItems.add(failed);
                                long completed = Math.min((long) pageNo * TRANSLATE_BATCH_SIZE, total);
                                Map<String, Object> payload = new HashMap<>();
                                payload.put("phase", "progress");
                                payload.put("total", total);
                                payload.put("completed", completed);
                                payload.put("percent", total == 0 ? 100 : (int) (completed * 100 / total));
                                return ServerSentEvent.<ReqResult<Map<String, Object>>>builder()
                                        .event("progress")
                                        .data(ReqResult.success(payload))
                                        .build();
                            } finally {
                                RequestContext.remove();
                            }
                        }).subscribeOn(Schedulers.boundedElastic()));
        Flux<ServerSentEvent<ReqResult<Map<String, Object>>>> doneStream = Mono.fromCallable(() -> {
            Map<String, Object> payload = new HashMap<>();
            payload.put("phase", "done");
            payload.put("success", failedItems.isEmpty());
            payload.put("total", total);
            payload.put("completed", total);
            payload.put("percent", 100);
            payload.put("failedCount", failedItems.size());
            payload.put("failedItems", failedItems);
            return ServerSentEvent.<ReqResult<Map<String, Object>>>builder()
                    .event("done")
                    .data(ReqResult.success(payload))
                    .build();
        }).flux();
        Flux<ServerSentEvent<ReqResult<Map<String, Object>>>> businessStream = progressStream.concatWith(doneStream);
        Flux<ServerSentEvent<ReqResult<Map<String, Object>>>> heartbeatStream = Flux.interval(Duration.ofSeconds(10))
                .map(seq -> ServerSentEvent.<ReqResult<Map<String, Object>>>builder()
                        .event("ping")
                        .data(ReqResult.success(Map.of("phase", "ping", "ts", System.currentTimeMillis())))
                        .build())
                .takeUntilOther(businessStream.ignoreElements());
        return Flux.merge(businessStream, heartbeatStream);
    }

    /**
     * 新增或更新多语言配置åå
     */
    @RequireResource(I18N_LANG_MODIFY)
    @Operation(summary = "新增或更新多语言配置")
    @PostMapping(value = "/config/addOrUpdate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> addOrUpdateI18nConfig(@RequestBody @Valid I18nConfigDto dto) {
        log.info("[addOrUpdateI18nConfig] 新增或更新多语言配置，type={}, module={}, fieldKey={}",
                dto.getType(), dto.getModule(), dto.getKey());
        i18nApplicationService.addOrUpdateI18nConfig(dto);
        return ReqResult.success();
    }

    @RequireResource(I18N_LANG_MODIFY)
    @Operation(summary = "批量新增或更新多语言配置")
    @PostMapping(value = "/config/batchAddOrUpdate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> batchAddOrUpdateI18nConfig(@RequestBody @Valid List<I18nConfigDto> configs) {
        Assert.notEmpty(configs, "The configuration list cannot be empty.");
        Assert.isTrue(configs.size() <= 10000, "Supports up to 10,000 configurations per batch.");
        i18nApplicationService.batchAddOrUpdateI18nConfig(configs);
        return ReqResult.success();
    }

    @RequireResource(I18N_LANG_DELETE)
    @Operation(summary = "批量删除多语言配置")
    @PostMapping(value = "/config/batchDelete", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> batchDeleteI18nConfig(@RequestBody List<I18nConfigDto> configs) {
        Assert.notEmpty(configs, "The configuration list cannot be empty.");
        i18nApplicationService.batchDeleteI18nConfig(configs);
        return ReqResult.success();
    }

    @SaasAdmin
    @Operation(hidden = true, summary = "比对两个版本的 i18n 配置并生成新增/变更差异文件")
    @GetMapping(value = "/config/diff", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<String> diffConfig(@RequestParam String fromVersion, @RequestParam String toVersion) {
        try {
            var split = i18nConfigDiffService.generateDiff(fromVersion, toVersion);
            String baseDir = "./" + I18nSyncConstants.I18N_JSON_EXPORT_BASE_PATH + "/";

            String addFileName = I18nSyncConstants.buildI18nConfigAddFileName(toVersion);
            writeDiffJsonFile(baseDir + addFileName, split.getAddRows());

            String updateFileName = I18nSyncConstants.buildI18nConfigUpdateFileName(toVersion);
            writeDiffJsonFile(baseDir + updateFileName, split.getUpdateRows());

            return ReqResult.success("已生成差异文件: " + addFileName + "（新增 " + split.getAddRows().size()
                    + " 条）, " + updateFileName + "（变更 " + split.getUpdateRows().size() + " 条）");
        } catch (IOException e) {
            return ReqResult.error(e.getMessage());
        }
    }

    private void writeDiffJsonFile(String saveToPath, Object rows) throws IOException {
        Path path = Paths.get(saveToPath).toAbsolutePath();
        Files.createDirectories(path.getParent());
        Files.writeString(path, JsonSerializeUtil.toJSONString(rows), StandardCharsets.UTF_8);
    }

    @SaasAdmin
    @Operation(hidden = true, summary = "将指定版本的新增/变更差异配置导入到租户")
    @GetMapping(value = "/config/import-diff", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<String> importDiffConfig(@RequestParam Long tenantId, @RequestParam String version) {
        Tenant tenant = tenantService.getById(tenantId);
        if (tenant == null) {
            return ReqResult.error("租户不存在");
        }
        i18nImportService.addConfigToTenant(tenant, version);
        i18nImportService.updateConfigToTenant(tenant, version);
        return ReqResult.success("差异配置导入成功（新增 + 变更）");
    }

    @SaasAdmin
    @Operation(hidden = true)
    @GetMapping(value = "/exportToFile", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Object> exportToFile(@RequestParam String version, @RequestParam List<String> langs) {
        try {
            I18nConfigExportDto dto = i18nExportService.exportConfig(version, langs);
            String json = JsonSerializeUtil.toJSONString(dto.getConfigs());
            String saveToPath = "./" + I18nSyncConstants.I18N_JSON_EXPORT_BASE_PATH + "/"
                    + I18nSyncConstants.buildI18nConfigExportFileName(version);
            Path path = Paths.get(saveToPath).toAbsolutePath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, json, StandardCharsets.UTF_8);
            return ReqResult.success("已导出");
        } catch (IOException e) {
            return ReqResult.error(e.getMessage());
        }
    }

    @SaasAdmin
    @Operation(hidden = true)
    @GetMapping(value = "/importFromFile", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<String> importFromFile(@RequestParam Long tenantId, @RequestParam String version) {
        Tenant tenant = tenantService.getById(tenantId);
        if (tenant == null) {
            return ReqResult.error("租户不存在");
        }
        i18nImportService.importLangToTenant(tenant, version);
        i18nImportService.importConfigToTenant(tenant, version);
        return ReqResult.success("导入成功");
    }
}
