package com.xspaceagi.agent.web.ui.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.web.ui.controller.dto.ConfigSelectAgentQueryDto;
import com.xspaceagi.agent.web.ui.controller.dto.ManagePublishedQueryDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.PageQueryVo;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "系统管理-发布管理相关接口")
@RestController
@RequestMapping("/api/system/publish")
@Slf4j
public class PublishManageController {

    @Resource
    private PublishApplicationService publishApplicationService;

    @RequireResource(PUBLISH_AUDIT_PASS)
    @Operation(summary = "审核通过")
    @RequestMapping(path = "/{applyId}", method = RequestMethod.POST)
    public ReqResult<Void> publish(@PathVariable @Schema(description = "申请ID") Long applyId) {
        PublishApplyDto agentPublishApplyDto = publishApplicationService.queryPublishApplyById(applyId);
        if (agentPublishApplyDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentApplyIdInvalid);
        }
        publishApplicationService.publish(applyId);
        return ReqResult.success();
    }

    @RequireResource(PUBLISH_AUDIT_REJECT)
    @Operation(summary = "审核拒绝")
    @RequestMapping(path = "/reject/{applyId}", method = RequestMethod.POST)
    public ReqResult<Void> reject(@PathVariable @Schema(description = "申请ID") Long applyId, @RequestBody PublishRejectDto publishRejectDto) {
        PublishApplyDto agentPublishApplyDto = publishApplicationService.queryPublishApplyById(applyId);
        if (agentPublishApplyDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentApplyIdInvalid);
        }
        publishRejectDto.setApplyId(applyId);
        publishApplicationService.rejectPublish(publishRejectDto);
        return ReqResult.success();
    }

    @RequireResource(PUBLISHED_MANAGE_OFFLINE)
    @Operation(summary = "下架")
    @RequestMapping(path = "/offShelf/{publishedId}", method = RequestMethod.POST)
    public ReqResult<Void> offShelf(@PathVariable @Schema(description = "发布ID") Long publishedId, @RequestBody OffShelfDto offShelfDto) {
        offShelfDto.setPublishId(publishedId);
        publishApplicationService.offShelf(offShelfDto);
        return ReqResult.success();
    }

    /**
     * 查询发布申请列表
     */
    @RequireResource(PUBLISH_AUDIT_QUERY_LIST)
    @Operation(summary = "查询发布申请列表")
    @RequestMapping(path = "/apply/list", method = RequestMethod.POST)
    public ReqResult<IPage<PublishApplyDto>> applyList(@RequestBody PageQueryVo<PublishApplyQueryDto> pageQueryVo) {
        IPage<PublishApplyDto> page = publishApplicationService.queryPublishApplyList(pageQueryVo);
        return ReqResult.success(page);
    }

    /**
     * 查询发布申请列表
     */
    @RequireResource(PUBLISHED_MANAGE_QUERY_LIST)
    @Operation(summary = "查询已发布列表")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<IPage<PublishedDto>> list(@RequestBody PageQueryVo<ManagePublishedQueryDto> pageQueryVo) {
        PublishedQueryDto publishedQueryDto = new PublishedQueryDto();
        if (pageQueryVo.getQueryFilter() != null) {
            publishedQueryDto.setKw(pageQueryVo.getQueryFilter().getKw());
            publishedQueryDto.setTargetType(pageQueryVo.getQueryFilter().getTargetType());
        }
        publishedQueryDto.setPage(pageQueryVo.getPageNo() != null ? pageQueryVo.getPageNo().intValue() : 1);
        publishedQueryDto.setPageSize(pageQueryVo.getPageSize() == null ? 10 : pageQueryVo.getPageSize().intValue());
        return ReqResult.success(publishApplicationService.queryPublishedListForManage(publishedQueryDto));
    }

    @RequireResource(SYSTEM_SETTING_SITE_AGENT)
    @Operation(summary = "查询可选择的智能体列表")
    @RequestMapping(path = "/agent/list", method = RequestMethod.POST)
    public ReqResult<List<PublishedDto>> queryAgentList(@RequestBody ConfigSelectAgentQueryDto configSelectAgentQueryDto) {
        PublishedQueryDto publishedQueryDto = new PublishedQueryDto();
        publishedQueryDto.setTargetType(Published.TargetType.Agent);
        publishedQueryDto.setKw(configSelectAgentQueryDto.getKw());
        publishedQueryDto.setPage(1);
        publishedQueryDto.setPageSize(100);
        List<PublishedDto> records = publishApplicationService.queryPublishedList(publishedQueryDto).getRecords();
        //查询已配置的智能体信息并追加到最前面
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        List<Long> agentIds = new ArrayList<>();
        if (tenantConfigDto.getDefaultAgentId() != null) {
            agentIds.add(tenantConfigDto.getDefaultAgentId());
        }
        if (CollectionUtils.isNotEmpty(tenantConfigDto.getDefaultAgentIds())) {
            agentIds.addAll(tenantConfigDto.getDefaultAgentIds());
        }
        if (CollectionUtils.isNotEmpty(agentIds) && StringUtils.isBlank(configSelectAgentQueryDto.getKw())) {
            List<PublishedDto> agentList = publishApplicationService.queryPublishedList(Published.TargetType.Agent, agentIds);
            if (CollectionUtils.isNotEmpty(agentList)) {
                records.addAll(0, agentList);
            }
        }
        //根据AgentId去重records
        records = records.stream().collect(Collectors.collectingAndThen(Collectors.toCollection(() -> new TreeSet<>(Comparator.comparing(PublishedDto::getTargetId))), ArrayList::new));
        return ReqResult.success(records);
    }
}
