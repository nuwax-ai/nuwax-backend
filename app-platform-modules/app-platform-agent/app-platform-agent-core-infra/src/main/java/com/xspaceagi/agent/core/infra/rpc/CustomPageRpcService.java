package com.xspaceagi.agent.core.infra.rpc;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.PageArgConfig;
import com.xspaceagi.agent.core.infra.rpc.dto.PageDto;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.custompage.sdk.ICustomPageRpcService;
import com.xspaceagi.custompage.sdk.dto.CustomPageDto;

import jakarta.annotation.Resource;

@Service
public class CustomPageRpcService {

    @Resource
    private ICustomPageRpcService iCustomPageRpcService;

    public PageDto queryPageDto(Long pageId) {
        return queryPageDto(pageId, true);
    }

    public PageDto queryPageDto(Long pageId, boolean published) {
        CustomPageDto customPageDto = iCustomPageRpcService.queryDetail(pageId);
        if (customPageDto == null) {
            return null;
        }
        if (published && (customPageDto.getBuildRunning() == null || !customPageDto.getBuildRunning())) {
            return null;
        }

        PageDto pageDto = new PageDto();
        pageDto.setNeedLogin(customPageDto.getNeedLogin() == null || customPageDto.getNeedLogin());
        pageDto.setCreatorId(customPageDto.getCreatorId());
        if (customPageDto.getDataSources() != null) {
            pageDto.setDataSources(customPageDto.getDataSources());
            customPageDto.getDataSources().forEach(dataSource -> {
                if (StringUtils.isBlank(dataSource.getKey())) {
                    dataSource.setKey(dataSource.getId().toString());
                }
            });
        }
        pageDto.setId(customPageDto.getProjectId());
        pageDto.setSpaceId(customPageDto.getSpaceId());
        pageDto.setIcon(customPageDto.getIcon());
        pageDto.setDescription(customPageDto.getDescription());
        pageDto.setName(customPageDto.getName());
        pageDto.setBasePath(customPageDto.getBasePath());
        pageDto.setCoverImg(customPageDto.getCoverImg());
        pageDto.setCoverImgSourceType(customPageDto.getCoverImgSourceType());
        pageDto.setDevAgentId(customPageDto.getDevAgentId());
        if (customPageDto.getPageArgConfigs() != null) {
            List<PageArgConfig> pageArgConfigs = customPageDto.getPageArgConfigs().stream().map(pageArgConfig -> {
                PageArgConfig pageUriConfig = new PageArgConfig();
                pageUriConfig.setBasePath(customPageDto.getBasePath());
                pageUriConfig.setPageId(customPageDto.getProjectId());
                pageUriConfig.setPageUri(pageArgConfig.getPageUri());
                pageUriConfig.setName(pageArgConfig.getName());
                pageUriConfig.setDescription(pageArgConfig.getDescription());
                if (pageArgConfig.getArgs() == null) {
                    pageArgConfig.setArgs(new ArrayList<>());
                }
                pageUriConfig.setArgs(pageArgConfig.getArgs().stream().map(arg -> {
                    Arg pageArg = new Arg();
                    pageArg.setKey(arg.getKey());
                    pageArg.setName(arg.getName());
                    pageArg.setDescription(arg.getDescription());
                    pageArg.setDataType(arg.getDataType() == null ? DataTypeEnum.String : DataTypeEnum.valueOf(arg.getDataType().name()));
                    pageArg.setRequire(arg.isRequire());
                    pageArg.setInputType(arg.getInputType() == null ? Arg.InputTypeEnum.Query : Arg.InputTypeEnum.valueOf(arg.getInputType().name()));
                    pageArg.setBindValue(arg.getBindValue());
                    pageArg.setEnable(arg.getEnable());
                    return pageArg;
                }).collect(Collectors.toList()));
                return pageUriConfig;
            }).collect(Collectors.toList());
            pageDto.setPageArgConfigs(pageArgConfigs);
        } else {
            pageDto.setPageArgConfigs(new ArrayList<>());
        }
        List<PageArgConfig> homeIndex = pageDto.getPageArgConfigs().stream().filter(arg -> arg.getPageUri().equals("/") || arg.getPageUri().equals("/index.html")).collect(Collectors.toList());
        if (homeIndex.size() == 0) {
            PageArgConfig pageArgConfig = new PageArgConfig();
            pageArgConfig.setBasePath(pageDto.getBasePath());
            pageArgConfig.setPageId(customPageDto.getProjectId());
            pageArgConfig.setPageUri("/");
            pageArgConfig.setName(pageDto.getName());
            pageArgConfig.setDescription(pageDto.getDescription());
            pageArgConfig.setArgs(new ArrayList<>());
            pageDto.getPageArgConfigs().add(pageArgConfig);
        }
        pageDto.getPageArgConfigs().forEach(pageArgConfig -> {
            String pageUri = pageArgConfig.getPageUri().startsWith("/") ? pageArgConfig.getPageUri() : "/" + pageArgConfig.getPageUri();
            pageArgConfig.setPageUri(pageUri);
            if (pageArgConfig.getArgs() == null) {
                pageArgConfig.setArgs(new ArrayList<>());
            }
        });
        return pageDto;
    }

    public List<PageDto> queryPageListByAgentIds(List<Long> agentIds) {
        List<CustomPageDto> customPageDtoList = iCustomPageRpcService.listByAgentIds(agentIds);
        if (customPageDtoList == null || customPageDtoList.isEmpty()) {
            return new ArrayList<>();
        }
        return customPageDtoList.stream()
                .map(this::convertToPageDto)
                .collect(Collectors.toList());
    }

    /**
     * 转换为PageDto
     */
    private PageDto convertToPageDto(CustomPageDto customPageDto) {
        if (customPageDto == null) {
            return null;
        }
        PageDto pageDto = new PageDto();
        pageDto.setNeedLogin(customPageDto.getNeedLogin() == null || customPageDto.getNeedLogin());
        pageDto.setCreatorId(customPageDto.getCreatorId());
        if (customPageDto.getDataSources() != null) {
            pageDto.setDataSources(customPageDto.getDataSources());
            customPageDto.getDataSources().forEach(dataSource -> {
                if (StringUtils.isBlank(dataSource.getKey())) {
                    dataSource.setKey(dataSource.getId().toString());
                }
            });
        }
        pageDto.setId(customPageDto.getProjectId());
        pageDto.setSpaceId(customPageDto.getSpaceId());
        pageDto.setIcon(customPageDto.getIcon());
        pageDto.setDescription(customPageDto.getDescription());
        pageDto.setName(customPageDto.getName());
        pageDto.setBasePath(customPageDto.getBasePath());
        pageDto.setCoverImg(customPageDto.getCoverImg());
        pageDto.setCoverImgSourceType(customPageDto.getCoverImgSourceType());
        pageDto.setDevAgentId(customPageDto.getDevAgentId());
        if (customPageDto.getPageArgConfigs() != null) {
            List<PageArgConfig> pageArgConfigs = customPageDto.getPageArgConfigs().stream().map(pageArgConfig -> {
                PageArgConfig pageUriConfig = new PageArgConfig();
                pageUriConfig.setBasePath(customPageDto.getBasePath());
                pageUriConfig.setPageId(customPageDto.getProjectId());
                pageUriConfig.setPageUri(pageArgConfig.getPageUri());
                pageUriConfig.setName(pageArgConfig.getName());
                pageUriConfig.setDescription(pageArgConfig.getDescription());
                if (pageArgConfig.getArgs() == null) {
                    pageArgConfig.setArgs(new ArrayList<>());
                }
                pageUriConfig.setArgs(pageArgConfig.getArgs().stream().map(arg -> {
                    Arg pageArg = new Arg();
                    pageArg.setKey(arg.getKey());
                    pageArg.setName(arg.getName());
                    pageArg.setDescription(arg.getDescription());
                    pageArg.setDataType(arg.getDataType() == null ? DataTypeEnum.String : DataTypeEnum.valueOf(arg.getDataType().name()));
                    pageArg.setRequire(arg.isRequire());
                    pageArg.setInputType(arg.getInputType() == null ? Arg.InputTypeEnum.Query : Arg.InputTypeEnum.valueOf(arg.getInputType().name()));
                    pageArg.setBindValue(arg.getBindValue());
                    pageArg.setEnable(arg.getEnable());
                    return pageArg;
                }).collect(Collectors.toList()));
                return pageUriConfig;
            }).collect(Collectors.toList());
            pageDto.setPageArgConfigs(pageArgConfigs);
        } else {
            pageDto.setPageArgConfigs(new ArrayList<>());
        }
        List<PageArgConfig> homeIndex = pageDto.getPageArgConfigs().stream().filter(arg -> arg.getPageUri().equals("/") || arg.getPageUri().equals("/index.html")).collect(Collectors.toList());
        if (homeIndex.size() == 0) {
            PageArgConfig pageArgConfig = new PageArgConfig();
            pageArgConfig.setBasePath(pageDto.getBasePath());
            pageArgConfig.setPageId(customPageDto.getProjectId());
            pageArgConfig.setPageUri("/");
            pageArgConfig.setName(pageDto.getName());
            pageArgConfig.setDescription(pageDto.getDescription());
            pageArgConfig.setArgs(new ArrayList<>());
            pageDto.getPageArgConfigs().add(pageArgConfig);
        }
        pageDto.getPageArgConfigs().forEach(pageArgConfig -> {
            String pageUri = pageArgConfig.getPageUri().startsWith("/") ? pageArgConfig.getPageUri() : "/" + pageArgConfig.getPageUri();
            pageArgConfig.setPageUri(pageUri);
            if (pageArgConfig.getArgs() == null) {
                pageArgConfig.setArgs(new ArrayList<>());
            }
        });
        return pageDto;
    }
}
