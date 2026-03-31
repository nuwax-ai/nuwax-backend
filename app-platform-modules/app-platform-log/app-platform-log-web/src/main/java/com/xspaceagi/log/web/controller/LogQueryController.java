package com.xspaceagi.log.web.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.log.sdk.request.DocumentSearchRequest;
import com.xspaceagi.log.sdk.service.ISearchRpcService;
import com.xspaceagi.log.sdk.vo.LogDocument;
import com.xspaceagi.log.sdk.vo.SearchResult;
import com.xspaceagi.log.web.controller.base.BaseController;
import com.xspaceagi.log.web.controller.dto.LogDetailQueryDto;
import com.xspaceagi.log.web.controller.dto.LogQueryDto;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.page.PageQueryVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.xspaceagi.system.spec.enums.ResourceEnum.SPACE_LOG_QUERY_DETAIL;
import static com.xspaceagi.system.spec.enums.ResourceEnum.SPACE_LOG_QUERY_LIST;

@Tag(name = "日志平台-搜索接口")
@RestController
@RequestMapping("/api/requestLogs")
@Slf4j
public class LogQueryController extends BaseController {

    @Resource
    private ISearchRpcService iSearchRpcService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @RequireResource(SPACE_LOG_QUERY_LIST)
    @Operation(summary = "统一日志查询（工作空间）")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<IPage<LogDocument>> search(@RequestBody PageQueryVo<LogQueryDto> pageQueryVo) {
        var filter = pageQueryVo.getQueryFilter();
        if (filter == null) {
            return ReqResult.error("查询条件不能为空");
        }
        if (filter.getSpaceId() == null) {
            return ReqResult.error("空间ID不能为空");
        }

        spacePermissionService.checkSpaceUserPermission(filter.getSpaceId());
        return search0(pageQueryVo);
    }

    @Operation(summary = "统一日志查询（API调用）")
    @RequestMapping(path = "/apikey/list", method = RequestMethod.POST)
    public ReqResult<IPage<LogDocument>> apiInvokeSearch(@RequestBody PageQueryVo<LogQueryDto> pageQueryVo) {
        var filter = pageQueryVo.getQueryFilter();
        if (filter == null) {
            pageQueryVo.setQueryFilter(new LogQueryDto());
            filter = pageQueryVo.getQueryFilter();
        }
        filter.setUserId(RequestContext.get().getUserId());
        return search0(pageQueryVo);
    }


    @RequireResource(SPACE_LOG_QUERY_DETAIL)
    @Operation(summary = "日志详情")
    @RequestMapping(path = "/detail", method = RequestMethod.POST)
    public ReqResult<LogDocument> detail(@RequestBody LogDetailQueryDto logDetailQueryDto) {

        DocumentSearchRequest.DocumentSearchRequestBuilder builder = DocumentSearchRequest.builder();
        builder.searchDocumentClazz(LogDocument.class);
        List<Map<String, Object>> filterFieldsAndValues = new ArrayList<>();
        builder.filterFieldsAndValues(filterFieldsAndValues);
        if (StringUtils.isNoneBlank(logDetailQueryDto.getId())) {
            filterFieldsAndValues.add(Map.of("id", logDetailQueryDto.getId()));
        }

        SearchResult search = iSearchRpcService.search(builder.build());
        LogDocument logDocument = search.getItems().stream().map(item -> (LogDocument) item.getDocument()).findFirst().orElse(null);
        if (logDocument == null) {
            return ReqResult.error("日志不存在");
        }
        if (!RequestContext.get().getUserId().equals(logDocument.getUserId())) {
            spacePermissionService.checkSpaceUserPermission(logDocument.getSpaceId());
        }
        return ReqResult.success(logDocument);
    }

}
