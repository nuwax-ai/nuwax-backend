package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Locale;

/**
 * 语言白名单单项：BCP 47 标识 + 基于 tag 的展示名（JDK CLDR，运行时生成）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "语言选项（标识 + 展示名）")
public class SupportedLocaleOptionDto implements Serializable {

    @Schema(description = "BCP 47 语言标识，如 zh-CN、en-US")
    private String tag;

    @Schema(description = "根据 tag 自动生成的展示名")
    private String name;

    public static SupportedLocaleOptionDto fromTag(String tag) {
        Locale locale = Locale.forLanguageTag(tag);
        String normalizedTag = locale.toLanguageTag();
        return new SupportedLocaleOptionDto(
                normalizedTag,
                locale.getDisplayName(locale)
        );
    }
}
