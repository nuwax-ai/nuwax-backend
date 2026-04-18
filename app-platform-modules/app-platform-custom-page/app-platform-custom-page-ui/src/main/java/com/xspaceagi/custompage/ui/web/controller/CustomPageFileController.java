package com.xspaceagi.custompage.ui.web.controller;

import com.xspaceagi.custompage.application.service.ICustomPageFileApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Tag(name = "Web app", description = "Custom page web app APIs")
@RestController
@RequestMapping("/api/custom-page")
@Slf4j
@RequiredArgsConstructor
public class CustomPageFileController extends BaseController {

    @Resource
    private ICustomPageFileApplicationService customPageFileApplicationService;

    @Operation(summary = "Serve static file (page)", description = "Serve static file for current page build")
    @CrossOrigin // 确保 OPTIONS 预检请求被正确处理
//    @GetMapping("/static/{projectId}/**")
    public ResponseEntity<StreamingResponseBody> getStaticFile(@PathVariable("projectId") Long projectId, HttpServletRequest request) {
        log.info("[Web] access staticfilerequest, project Id={}", projectId);

        if (projectId == null) {
            log.error("[Web] Invalid project Id, project Id={}", projectId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        String requestPath = request.getRequestURI();
        String staticPrefix = "/api/custom-page/static/" + projectId + "/";
        String targetPrefix = "/custom-page/static/" + projectId + "/";

        return customPageFileApplicationService.getStaticFile(requestPath, staticPrefix, targetPrefix, String.valueOf(projectId), getUser());
    }

    @Operation(summary = "Serve static file (historical page)", description = "Serve static file for a historical page version")
    @CrossOrigin // 确保 OPTIONS 预检请求被正确处理
//    @GetMapping("/static/_his/{projectId}/**")
    public ResponseEntity<StreamingResponseBody> getStaticHisFile(@PathVariable("projectId") Long projectId, HttpServletRequest request) {
        log.info("[Web] access staticfile(his)request, project Id={}", projectId);

        if (projectId == null) {
            log.error("[Web] Invalid project Id, project Id={}", projectId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        String requestPath = request.getRequestURI();
        String staticPrefix = "/api/custom-page/static/_his/" + projectId + "/";
        String targetPrefix = "/custom-page/static/_his/" + projectId + "/";

        return customPageFileApplicationService.getStaticFile(requestPath, staticPrefix, targetPrefix, String.valueOf(projectId), getUser());
    }
}