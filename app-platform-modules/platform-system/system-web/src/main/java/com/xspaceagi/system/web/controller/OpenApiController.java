package com.xspaceagi.system.web.controller;

import com.xspaceagi.system.infra.dao.entity.OpenApiDefinition;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.infra.dao.service.OpenApiDefinitionService;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.PermissionTargetTypeEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Tag(name = "开放API", description = "开放API相关接口")
@RestController
@RequestMapping("/api/system/open-api")
public class OpenApiController {

    @Resource
    private OpenApiDefinitionService openApiDefinitionService;

    @Operation(summary = "查询开放API列表")
    @GetMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<List<OpenApiDefinition>> queryAll(@RequestParam("targetType") Integer targetType) {
        if (PermissionTargetTypeEnum.isInValid(targetType)) {
            return ReqResult.error("参数targetType错误");
        }
        List<OpenApiDefinition> list = openApiDefinitionService.queryAll();
        PermissionTargetTypeEnum targetTypeEnum = PermissionTargetTypeEnum.getByCode(targetType);

        if (targetTypeEnum == PermissionTargetTypeEnum.GROUP) {
            List<OpenApiDefinition> filteredList = filterUserRoleApis(list);
            log.info("[queryAll] List open APIs, targetType={}, size={}", targetType, filteredList.size());
            return ReqResult.success(filteredList);
        }
        log.info("[queryAll] List open APIs, targetType={}, size={}", targetType, list == null ? 0 : list.size());
        return ReqResult.success(list);
    }

    private List<OpenApiDefinition> filterUserRoleApis(List<OpenApiDefinition> list) {
        List<OpenApiDefinition> result = new ArrayList<>();
        if (list == null) {
            return result;
        }
        for (OpenApiDefinition item : list) {
            OpenApiDefinition copied = copyAndFilter(item);
            if (copied != null) {
                result.add(copied);
            }
        }
        return result;
    }

    private OpenApiDefinition copyAndFilter(OpenApiDefinition item) {
        if (item == null) {
            return null;
        }
        User.Role role = item.getRole();
        if (role != null && role != User.Role.User) {
            return null;
        }
        OpenApiDefinition copied = new OpenApiDefinition();
        copied.setName(item.getName());
        copied.setKey(item.getKey());
        copied.setRole(item.getRole());
        copied.setPath(item.getPath());

        if (item.getApiList() != null) {
            List<OpenApiDefinition> children = new ArrayList<>();
            for (OpenApiDefinition child : item.getApiList()) {
                OpenApiDefinition copiedChild = copyAndFilter(child);
                if (copiedChild != null) {
                    children.add(copiedChild);
                }
            }
            copied.setApiList(children);
        }
        return copied;
    }
}
