package com.xspaceagi.knowledge.sdk.vo;

import com.xspaceagi.knowledge.sdk.enums.SegmentEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
public class DocumentAddRequestVo implements Serializable {

    @Schema(description = "知识库ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long kbId;

    @Schema(description = "文档名称")
    private String name;

    @Schema(description = "文件内容")
    private String fileContent;

    @Schema(description = "文件URL")
    private String docUrl;

    @Schema(description = "分段类型，words: 按照词数分段, delimiter: 按照分隔符分段，field: 按照字段分段")
    private SegmentEnum segment;

    @Schema(description = "分段最大字符数，选择words或delimiter时有效")
    private Integer words;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "用户名称")
    private String userName;

}
