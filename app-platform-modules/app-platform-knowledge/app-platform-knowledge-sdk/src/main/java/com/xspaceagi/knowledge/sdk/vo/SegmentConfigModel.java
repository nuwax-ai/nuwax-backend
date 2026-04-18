package com.xspaceagi.knowledge.sdk.vo;

import com.xspaceagi.knowledge.sdk.enums.SegmentEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

@Schema(description = "分段配置")
@Data
public class SegmentConfigModel implements Serializable {

    @Schema(description = "分段类型，words: 按照词数分段, delimiter: 按照分隔符分段，field: 按照字段分段")
    private SegmentEnum segment;

    @Schema(description = "分段最大字符数，选择words或delimiter时有效")
    private Integer words;

    @Schema(description = "分段重叠字符数，建议设置words的10%-25%")
    @Max(value = 100, message = "Overlap characters cannot exceed 100")
    @Min(value = 0, message = "Overlap characters cannot be less than 0")
    private Integer overlaps;

    @Schema(description = "分隔符，仅在选择delimiter时有效")
    private String delimiter;

    @Schema(description = "是否去除连续空白、制表符和空行等，默认为True")
    private Boolean isTrim;

//    @Deprecated
//    @Schema(description = "问题字段，仅在选择field时有效")
//    private String questionField;
//
//    @Deprecated
//    @Schema(description = "答案字段，仅在选择field时有效")
//    private String answerField;


    public static SegmentConfigModel obtainDefaultModel() {
        //自动使用默认值
        SegmentConfigModel segmentConfig = new SegmentConfigModel();
        segmentConfig.setSegment(SegmentEnum.WORDS);
        segmentConfig.setWords(1000);
        segmentConfig.setOverlaps(10);
        segmentConfig.setIsTrim(true);

        return segmentConfig;
    }


}
