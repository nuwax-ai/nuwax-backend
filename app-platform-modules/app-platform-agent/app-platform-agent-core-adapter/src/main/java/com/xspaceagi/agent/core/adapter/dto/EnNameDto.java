package com.xspaceagi.agent.core.adapter.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

import java.io.Serializable;

@Data
public class EnNameDto implements Serializable {

    @JsonPropertyDescription("根据用户输入生成一个英文名称，如果输入本身不是中文，则直接返回，如果中文没有任何含义可以返回拼音。不超过32个字符，不能出现空格，使用小写，多个单词用下划线隔开，例如 web_search")
    private String enName;

}
