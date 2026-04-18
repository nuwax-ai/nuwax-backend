package com.xspaceagi.log.web.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.domain.model.AgentLogModel;
import com.xspaceagi.domain.model.valueobj.AgentLogSearchParams;
import com.xspaceagi.log.app.service.LogPlatformApplicationService;
import com.xspaceagi.log.sdk.request.AgentLogDetailParamsRequest;
import com.xspaceagi.log.web.controller.base.BaseController;
import com.xspaceagi.log.web.controller.dto.AgentLogSearchParamsRequest;
import com.xspaceagi.system.sdk.operate.ActionType;
import com.xspaceagi.system.sdk.operate.OperationLogReporter;
import com.xspaceagi.system.sdk.operate.SystemEnum;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.LogPlatformException;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "日志平台-搜索接口")
@RestController
@RequestMapping("/api/logPlatform/agent")
@Slf4j
public class LogSearchController extends BaseController {

        @Resource
        private SpaceApplicationService spaceApplicationService;

        @Resource
        private LogPlatformApplicationService logPlatformApplicationService;

        @OperationLogReporter(actionType = ActionType.QUERY, action = "数据列表查询", objectName = "智能体日志", systemCode = SystemEnum.LOG_PLATFORM)
        @Operation(summary = "日志查询")
        @RequestMapping(path = "/list", method = RequestMethod.POST)
        public ReqResult<IPage<AgentLogModel>> search(
                        @RequestBody PageQueryVo<AgentLogSearchParamsRequest> pageQueryVo) {
                var userContext = this.getUser();
                var userId = userContext.getUserId();

                var filter = pageQueryVo.getQueryFilter();
                if (filter == null) {
                        return ReqResult.error("查询条件不能为空");
                }

                // 查询用户有权限的空间,限制访问空间
                var spaceList = this.spaceApplicationService.queryListByUserId(userId);
                var authSpaceIds = spaceList.stream().map(space -> String.valueOf(space.getId())).toList();

                var tenantId = userContext.getTenantId();

                // 转换前端请求为领域模型
                AgentLogSearchParams searchParams = AgentLogSearchParamsRequest.convertFrom(filter);

                // 设置租户ID（从用户上下文获取）
                searchParams.setTenantId(tenantId.toString());
                searchParams.setSpaceId(authSpaceIds);

                try {
                        // 使用 logPlatformApplicationService 接口查询日志
                        IPage<AgentLogModel> result = logPlatformApplicationService.searchAgentLogs(
                                        searchParams,
                                        pageQueryVo.getCurrent(),
                                        pageQueryVo.getPageSize());

                        return ReqResult.success(result);
                } catch (LogPlatformException e) {
                        log.error("Log search failed: {}", e.getMessage(), e);
                        return ReqResult.error(e.getCode(), e.getMessage());
                } catch (Exception e) {
                        log.error("Unknown error during log search", e);
                        return ReqResult.error("日志搜索发生未知错误: " + e.getMessage());
                }
        }

        @OperationLogReporter(actionType = ActionType.QUERY, action = "数据列表查询", objectName = "智能体日志", systemCode = SystemEnum.LOG_PLATFORM)
        @Operation(summary = "日志详情")
        @RequestMapping(path = "/detail", method = RequestMethod.POST)
        public ReqResult<AgentLogModel> detail(
                        @RequestBody AgentLogDetailParamsRequest request) {
                var userContext = this.getUser();
                var userId = userContext.getUserId();

                // 查询用户有权限的空间,限制访问空间
                var spaceList = this.spaceApplicationService.queryListByUserId(userId);
                var authSpaceIds = spaceList.stream().map(space -> String.valueOf(space.getId())).toList();

                var tenantId = userContext.getTenantId();

                // 转换前端请求为领域模型
                AgentLogSearchParams searchParams = AgentLogSearchParamsRequest.convertFrom(request);

                // 设置租户ID（从用户上下文获取）
                searchParams.setTenantId(tenantId.toString());
                searchParams.setSpaceId(authSpaceIds);

                try {
                        // 使用 logPlatformApplicationService 接口查询日志
                        AgentLogModel result = logPlatformApplicationService.queryOneAgentLog(searchParams);

                        return ReqResult.success(result);
                } catch (LogPlatformException e) {
                        log.error("日志详情失败: {}", e.getMessage(), e);
                        return ReqResult.error(e.getCode(), e.getMessage());
                } catch (Exception e) {
                        log.error("Unknown error while loading log detail", e);
                        return ReqResult.error("日志搜索发生未知错误: " + e.getMessage());
                }
        }

}
