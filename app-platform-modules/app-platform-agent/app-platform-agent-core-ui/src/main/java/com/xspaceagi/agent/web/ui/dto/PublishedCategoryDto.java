package com.xspaceagi.agent.web.ui.dto;

import com.xspaceagi.system.spec.annotation.I18n;
import com.xspaceagi.system.spec.annotation.I18nField;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@I18n(module = "Category")
@Data
public class PublishedCategoryDto implements Serializable {

    @I18nField(field = "code", keyPrefix = true)
    private String key;

    @I18nField(field = "name")
    private String label;

    private String icon;

    private CategoryType type;

    @I18nField(subObj = true)
    private List<PublishedCategoryDto> children;

    public enum CategoryType {
        Agent, Plugin, Workflow, Template, Skill, Component, PageApp
    }
}
