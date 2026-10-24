package com.xspaceagi.agent.core.adapter.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.xspaceagi.agent.core.spec.enums.AgentCategoryEnum;
import com.xspaceagi.agent.core.spec.enums.PageAppCategoryEnum;
import com.xspaceagi.agent.core.spec.enums.SkillCategoryEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

@Data
public class CategoryDto implements Serializable {

    @Schema(description = "类别名称")
    @JsonPropertyDescription("类别名称")
    private String name;

    @Schema(description = "类别描述")
    @JsonPropertyDescription("类别描述")
    private String description;

    public static List<CategoryDto> convertAgentCategoryList() {
        AgentCategoryEnum[] agentCategoryEnums = AgentCategoryEnum.values();
        //agentCategoryEnums转List<AgentCategoryDto>
        return List.of(agentCategoryEnums).stream().map(agentCategoryEnum -> {
            CategoryDto agentCategoryDto = new CategoryDto();
            agentCategoryDto.setName(agentCategoryEnum.getName());
            agentCategoryDto.setDescription(agentCategoryEnum.getDesc());
            return agentCategoryDto;
        }).collect(Collectors.toList());
    }

    public static List<CategoryDto> convertPageAppCategoryList() {
        PageAppCategoryEnum[] agentCategoryEnums = PageAppCategoryEnum.values();
        //agentCategoryEnums转List<AgentCategoryDto>
        return List.of(agentCategoryEnums).stream().map(agentCategoryEnum -> {
            CategoryDto agentCategoryDto = new CategoryDto();
            agentCategoryDto.setName(agentCategoryEnum.getName());
            agentCategoryDto.setDescription(agentCategoryEnum.getDesc());
            return agentCategoryDto;
        }).collect(Collectors.toList());
    }

    public static List<CategoryDto> convertPluginCategoryList() {
        PluginCategoryEnum[] pluginCategoryEnums = PluginCategoryEnum.values();
        return List.of(pluginCategoryEnums).stream().map(pluginCategoryEnum -> {
            CategoryDto categoryDto = new CategoryDto();
            categoryDto.setName(pluginCategoryEnum.getName());
            categoryDto.setDescription(pluginCategoryEnum.getDesc());
            return categoryDto;
        }).collect(Collectors.toList());
    }

    public static List<CategoryDto> convertSkillCategoryList() {
        SkillCategoryEnum[] skillCategoryEnums = SkillCategoryEnum.values();
        return List.of(skillCategoryEnums).stream().map(skillCategoryEnum -> {
            CategoryDto skillCategoryDto = new CategoryDto();
            skillCategoryDto.setName(skillCategoryEnum.getName());
            skillCategoryDto.setDescription(skillCategoryEnum.getDesc());
            return skillCategoryDto;
        }).collect(Collectors.toList());
    }

    public enum PluginCategoryEnum {
        //智能硬件
        Hardware("Hardware", "智能硬件"),
        NewsReading("NewsReading", "新闻阅读"),
        Life("Life", "便利生活"),
        Image("Image", "图像"),
        Utility("Utility", "实用工具"),
        WebSearch("WebSearch", "网页搜索"),
        ScienceAndEducation("ScienceAndEducation", "科学与教育"),
        Social("Social", "社交"),
        GameAndEntertainment("GameAndEntertainment", "游戏与娱乐"),
        FinanceAndCommerce("FinanceAndCommerce", "金融与商业"),
        Video("Video", "音视频"),
        Other("Other", "其他");
        private String name;
        private String desc;

        PluginCategoryEnum(String name, String desc) {
            this.name = name;
            this.desc = desc;
        }

        public String getName() {
            return name;
        }

        public String getDesc() {
            return desc;
        }
    }
}
