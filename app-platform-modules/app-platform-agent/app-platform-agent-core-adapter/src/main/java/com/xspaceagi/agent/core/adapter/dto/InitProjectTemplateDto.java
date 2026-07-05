package com.xspaceagi.agent.core.adapter.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Arrays;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "初始化项目模板请求DTO")
public class InitProjectTemplateDto implements Serializable {

    @Schema(description = "用户ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;

    @Schema(description = "会话ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("cId")
    private Long cId;

    @Schema(description = "项目类型", requiredMode = Schema.RequiredMode.REQUIRED)
    private ProjectType projectType;

    @Schema(description = "编程语言（projectType=AGENT时必填）")
    private ProgrammingLanguage programmingLanguage;

    public enum ProjectType {
        AGENT("agent"),
        SKILL("skill");

        private final String value;

        ProjectType(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static ProjectType fromValue(String value) {
            if (value == null) {
                return null;
            }
            return Arrays.stream(values())
                    .filter(e -> e.value.equalsIgnoreCase(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown projectType: " + value));
        }
    }

    public enum ProgrammingLanguage {
        TYPESCRIPT("typescript"),
        PYTHON("python");

        private final String value;

        ProgrammingLanguage(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static ProgrammingLanguage fromValue(String value) {
            if (value == null) {
                return null;
            }
            return Arrays.stream(values())
                    .filter(e -> e.value.equalsIgnoreCase(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown programmingLanguage: " + value));
        }
    }

}
