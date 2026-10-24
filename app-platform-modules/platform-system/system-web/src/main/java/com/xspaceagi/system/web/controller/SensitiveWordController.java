package com.xspaceagi.system.web.controller;

import com.xspaceagi.system.spec.common.RequestContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.system.application.dto.SensitiveWordCheckDto;
import com.xspaceagi.system.application.dto.SensitiveWordCheckResultDto;
import com.xspaceagi.system.application.dto.SensitiveWordCreateDto;
import com.xspaceagi.system.application.dto.SensitiveWordQueryDto;
import com.xspaceagi.system.application.dto.SensitiveWordStatusDto;
import com.xspaceagi.system.application.dto.SensitiveWordUpdateDto;
import com.xspaceagi.system.application.service.SensitiveWordApplicationService;
import com.xspaceagi.system.infra.dao.entity.SensitiveWord;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.web.controller.base.BaseController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Slf4j
@Tag(name = "敏感词管控", description = "敏感词管控相关接口")
@RestController
@RequestMapping("/api/system/sensitive/word")
public class SensitiveWordController extends BaseController {

    @Resource
    private SensitiveWordApplicationService sensitiveWordApplicationService;

    @RequireResource(SENSITIVE_WORD_ADD)
    @Operation(summary = "创建敏感词")
    @PostMapping(value = "/create", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<SensitiveWord> create(@RequestBody @Valid SensitiveWordCreateDto dto) {
        log.info("[create] Create sensitive word, word={}, category={}, matchType={}, action={}",
                dto.getWord(), dto.getCategory(), dto.getMatchType(), dto.getAction());
        return sensitiveWordApplicationService.create(dto, getUser());
    }

    @RequireResource(SENSITIVE_WORD_MODIFY)
    @Operation(summary = "更新敏感词")
    @PostMapping(value = "/update", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<SensitiveWord> update(@RequestBody @Valid SensitiveWordUpdateDto dto) {
        log.info("[update] Update sensitive word, id={}, word={}", dto.getId(), dto.getWord());
        return sensitiveWordApplicationService.update(dto, getUser());
    }

    @RequireResource(SENSITIVE_WORD_DELETE)
    @Operation(summary = "删除敏感词")
    @PostMapping(value = "/delete/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> delete(@PathVariable("id") Long id) {
        log.info("[delete] Delete sensitive word, id={}", id);
        return sensitiveWordApplicationService.delete(id, getUser());
    }

    @RequireResource(SENSITIVE_WORD_ENABLE)
    @Operation(summary = "启用/禁用敏感词")
    @PostMapping(value = "/updateStatus", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> updateStatus(@RequestBody @Valid SensitiveWordStatusDto dto) {
        log.info("[updateStatus] Update sensitive word status, id={}, status={}", dto.getId(), dto.getStatus());
        return sensitiveWordApplicationService.updateStatus(dto, getUser());
    }

    @RequireResource(SENSITIVE_WORD_QUERY)
    @Operation(summary = "分页查询敏感词")
    @PostMapping(value = "/page", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Page<SensitiveWord>> page(@RequestBody PageQueryVo<SensitiveWordQueryDto> pageQueryVo) {
        SensitiveWordQueryDto queryFilter = pageQueryVo.getQueryFilter() == null ? new SensitiveWordQueryDto() : pageQueryVo.getQueryFilter();
        log.info("[page] Page query sensitive word, current={}, pageSize={}, word={}, category={}, matchType={}, action={}, status={}",
                pageQueryVo.getCurrent(), pageQueryVo.getPageSize(),
                queryFilter.getWord(), queryFilter.getCategory(), queryFilter.getMatchType(), queryFilter.getAction(), queryFilter.getStatus());
        return ReqResult.success(sensitiveWordApplicationService.pageQuery(pageQueryVo));
    }

    @RequireResource(SENSITIVE_WORD_QUERY)
    @Operation(summary = "查询全部敏感词列表（条件可选）")
    @PostMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<List<SensitiveWord>> listAll(@RequestBody(required = false) SensitiveWordQueryDto dto) {
        SensitiveWordQueryDto queryDto = dto == null ? new SensitiveWordQueryDto() : dto;
        log.info("[listAll] List all sensitive word, word={}, category={}, matchType={}, action={}, status={}",
                queryDto.getWord(), queryDto.getCategory(), queryDto.getMatchType(), queryDto.getAction(), queryDto.getStatus());
        return ReqResult.success(sensitiveWordApplicationService.listAll(queryDto));
    }

//    @RequireResource(SENSITIVE_WORD_QUERY)
//    @Operation(summary = "查询已启用的敏感词列表")
//    @GetMapping(value = "/listEnabled", produces = MediaType.APPLICATION_JSON_VALUE)
//    public ReqResult<List<SensitiveWord>> listEnabled() {
//        return ReqResult.success(sensitiveWordApplicationService.listEnabled());
//    }

    @RequireResource(SENSITIVE_WORD_QUERY)
    @Operation(summary = "检测文本是否命中敏感词")
    @PostMapping(value = "/check", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<SensitiveWordCheckResultDto> check(@RequestBody @Valid SensitiveWordCheckDto dto) {
        log.info("[check] Check sensitive word, textLength={}", dto.getText().length());
        return ReqResult.success(sensitiveWordApplicationService.checkText(RequestContext.get().getTenantId(), dto.getText()));
    }
}
