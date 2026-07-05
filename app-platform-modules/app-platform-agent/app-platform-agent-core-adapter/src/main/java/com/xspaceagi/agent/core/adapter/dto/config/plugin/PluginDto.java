package com.xspaceagi.agent.core.adapter.dto.config.plugin;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.dto.CreatorDto;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.spec.enums.CodeConstant;
import com.xspaceagi.agent.core.spec.enums.CodeLanguageEnum;
import com.xspaceagi.agent.core.spec.enums.PluginTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
public class PluginDto implements Serializable {

    @Schema(description = "插件ID")
    private Long id;

    @Schema(description = "空间ID")
    private Long spaceId;

    @Schema(description = "创建人ID")
    private Long creatorId;

    @Schema(description = "插件名称")
    private String name;

    @Schema(description = "函数名", hidden = true)
    private String functionName;

    @Schema(description = "插件描述")
    private String description;

    @Schema(description = "插件图标")
    private String icon;

    @Schema(description = "插件类型")
    private PluginTypeEnum type;

    @Schema(description = "插件代码语言")
    private CodeLanguageEnum codeLang;

    @Schema(description = "插件发布状态")
    private Published.PublishStatus publishStatus;

    @Schema(description = "已发布的范围，用于发布时做默认选中")
    private Published.PublishScope scope;

    private Object config;

    private Date modified;

    private Date created;

    private String category;

    @Schema(description = "发布时间，如果不为空，与当前modified时间做对比，如果发布时间小于modified，则前端显示：有更新未发布")
    private Date publishDate;

    @Schema(description = "创建人信息")
    private CreatorDto creator;

    @Schema(description = "权限列表")
    private List<String> permissions;

    @Schema(description = "已发布的空间ID", hidden = true)
    private List<Long> publishedSpaceIds;

    @Schema(description = "开发时使用的会话ID")
    private Long devAgentConversationId;

    private String toolId;

    public static PluginDto convertToPluginDto(String config) {
        if (config == null) {
            return null;
        }
        PluginDto pluginDto = JSON.parseObject(config, PluginDto.class);
        pluginDto.setConfig(convertToPluginConfigDto(pluginDto, pluginDto.getConfig().toString()));
        return pluginDto;
    }

    public static PluginConfigDto convertToPluginConfigDto(PluginDto pluginDto, String pluginConfig) {
        PluginConfigDto pluginConfigDto;
        if (pluginDto.getType() == PluginTypeEnum.CODE) {
            CodePluginConfigDto codePluginConfigDto;
            if (pluginConfig != null) {
                codePluginConfigDto = JSON.parseObject(pluginConfig, CodePluginConfigDto.class);
            } else {
                codePluginConfigDto = new CodePluginConfigDto();
                codePluginConfigDto.setInputArgs(new ArrayList<>());
                codePluginConfigDto.setOutputArgs(new ArrayList<>());
            }
            if (StringUtils.isBlank(codePluginConfigDto.getCode())) {
                if (CodeLanguageEnum.JavaScript == pluginDto.getCodeLang()) {
                    codePluginConfigDto.setCode(CodeConstant.DEFAULT_CODE_JS);
                } else {
                    codePluginConfigDto.setCode(CodeConstant.DEFAULT_CODE_PYTHON);
                }
            }
            pluginConfigDto = codePluginConfigDto;
        } else if (pluginDto.getType() == PluginTypeEnum.HTTP) {
            HttpPluginConfigDto httpPluginConfigDto;
            if (pluginConfig != null) {
                httpPluginConfigDto = JSON.parseObject(pluginConfig, HttpPluginConfigDto.class);
            } else {
                httpPluginConfigDto = new HttpPluginConfigDto();
                httpPluginConfigDto.setInputArgs(new ArrayList<>());
                httpPluginConfigDto.setOutputArgs(new ArrayList<>());
            }
            pluginConfigDto = httpPluginConfigDto;
        } else {
            return null;
        }

        pluginConfigDto.setId(pluginDto.getId());
        pluginConfigDto.setName(pluginDto.getName());
        pluginConfigDto.setCodeLang(pluginDto.getCodeLang());
        pluginConfigDto.setType(pluginDto.getType());
        pluginConfigDto.setDescription(pluginDto.getDescription());
        List<Arg> inputArgs = pluginConfigDto.getInputArgs();
        if (inputArgs != null) {
            inputArgs.forEach(inputArg -> Arg.generateKey(inputArg.getName(), inputArg.getSubArgs(), null));
        }
        List<Arg> outputArgs = pluginConfigDto.getOutputArgs();
        if (outputArgs != null) {
            outputArgs.forEach(outputArg -> Arg.generateKey(outputArg.getName(), outputArg.getSubArgs(), null));
        }
        return pluginConfigDto;
    }
}