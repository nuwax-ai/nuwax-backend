package com.xspaceagi.log.web.controller.base;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.log.sdk.request.DocumentSearchRequest;
import com.xspaceagi.log.sdk.service.ISearchRpcService;
import com.xspaceagi.log.sdk.vo.LogDocument;
import com.xspaceagi.log.web.controller.dto.LogQueryDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.LogPlatformException;
import com.xspaceagi.system.spec.page.PageQueryVo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public abstract class BaseController {

    @Resource
    private ISearchRpcService iSearchRpcService;

    /**
     * 获取当前登录用户信息
     *
     * @return
     */
    public UserContext getUser() {
        var userDto = (UserDto) RequestContext.get().getUser();
        return UserContext.builder()
                .userId(userDto.getId())
                .userName(userDto.getUserName())
                .nickName(userDto.getNickName())
                .email(userDto.getEmail())
                .phone(userDto.getPhone())
                .status(userDto.getStatus() == User.Status.Enabled ? 1 : -1)
                .tenantId(userDto.getTenantId())
                .tenantName(null)
                .orgId(null)
                .orgName(null)
                .roleType(null)
                .build();
    }


    protected ReqResult<IPage<LogDocument>> search0(@RequestBody PageQueryVo<LogQueryDto> pageQueryVo) {
        var filter = pageQueryVo.getQueryFilter();
        if (filter == null) {
            filter = new LogQueryDto();
        }
        if (pageQueryVo.getPageSize() == null || pageQueryVo.getPageSize() <= 0) {
            pageQueryVo.setPageSize(10L);
        }
        if (pageQueryVo.getCurrent() == null || pageQueryVo.getCurrent() <= 0) {
            pageQueryVo.setCurrent(1L);
        }
        // 避免到最后一页报错
        if (pageQueryVo.getCurrent() * pageQueryVo.getPageSize() > 10000) {
            pageQueryVo.setCurrent(pageQueryVo.getCurrent() - 1);
        }
        DocumentSearchRequest.DocumentSearchRequestBuilder builder = DocumentSearchRequest.builder();
        builder.searchDocumentClazz(LogDocument.class);
        builder.from((int) (pageQueryVo.getPageSize() * (pageQueryVo.getCurrent() - 1)));
        builder.size(pageQueryVo.getPageSize().intValue());
        List<Map<String, Object>> filterFieldsAndValues = new ArrayList<>();
        builder.filterFieldsAndValues(filterFieldsAndValues);
        filterFieldsAndValues.add(Map.of("tenantId", RequestContext.get().getTenantId()));
        if (StringUtils.isNoneBlank(filter.getInput())) {
            filterFieldsAndValues.add(Map.of("input", Map.of("express", "match", "query", filter.getInput())));
        }
        if (StringUtils.isNoneBlank(filter.getOutput())) {
            filterFieldsAndValues.add(Map.of("output", Map.of("express", "match", "query", filter.getOutput())));
        }
        if (StringUtils.isNoneBlank(filter.getProcessData())) {
            filterFieldsAndValues.add(Map.of("processData", filter.getProcessData()));
        }
        if (StringUtils.isNoneBlank(filter.getRequestId())) {
            filterFieldsAndValues.add(Map.of("requestId", filter.getRequestId()));
        }
        if (StringUtils.isNoneBlank(filter.getResultCode())) {
            filterFieldsAndValues.add(Map.of("resultCode", filter.getResultCode()));
        }
        if (StringUtils.isNoneBlank(filter.getConversationId())) {
            filterFieldsAndValues.add(Map.of("conversationId", filter.getConversationId()));
        }
        if (StringUtils.isNoneBlank(filter.getTargetId())) {
            filterFieldsAndValues.add(Map.of("targetId", filter.getTargetId()));
        }
        if (StringUtils.isNoneBlank(filter.getTargetName())) {
            filterFieldsAndValues.add(Map.of("targetName", filter.getTargetName()));
        }
        if (StringUtils.isNoneBlank(filter.getTargetType())) {
            filterFieldsAndValues.add(Map.of("targetType", filter.getTargetType()));
        }
        if (StringUtils.isNoneBlank(filter.getUserName())) {
            filterFieldsAndValues.add(Map.of("userName", filter.getUserName()));
        }
        if (StringUtils.isNoneBlank(filter.getFrom())) {
            filterFieldsAndValues.add(Map.of("from", filter.getFrom()));
        }
        if (filter.getUserId() != null) {
            filterFieldsAndValues.add(Map.of("userId", filter.getUserId()));
        }
        if (filter.getSpaceId() != null) {
            filterFieldsAndValues.add(Map.of("spaceId", filter.getSpaceId()));
        }
        Map<String, Object> createTimeRange = new HashMap<>();
        if (filter.getCreateTimeGt() != null) {
            createTimeRange.put("gt", filter.getCreateTimeGt());
        }
        if (filter.getCreateTimeLt() != null) {
            createTimeRange.put("lt", filter.getCreateTimeLt());
        }
        if (!createTimeRange.isEmpty()) {
            createTimeRange.put("express", "range");
            filterFieldsAndValues.add(Map.of("createTime", createTimeRange));
        }
        builder.sortFieldsAndValues(Map.of("createTime", "Desc"));
        var result = iSearchRpcService.search(builder.build());

        try {
            IPage<LogDocument> page = new Page<>(pageQueryVo.getCurrent().intValue(), pageQueryVo.getPageSize().intValue());
            page.setTotal(result.getTotal());
            page.setRecords(result.getItems().stream().map(item -> {
                LogDocument document = (LogDocument) item.getDocument();
                document.setProcessData(null);
                return document;
            }).collect(Collectors.toList()));
            return ReqResult.success(page);
        } catch (LogPlatformException e) {
            log.error("日志搜索失败: {}", e.getMessage(), e);
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("日志搜索发生未知错误", e);
            return ReqResult.error("日志搜索发生未知错误: " + e.getMessage());
        }
    }

}
