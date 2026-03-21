package com.xspaceagi.im.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Schema(description  = "文件上传结果")
@Data
public class ImUploadResultDto implements Serializable {

     @Schema(description =  "文件完整的网络地址")
    private String url;

     @Schema(description =  "文件唯一标识")
    private String key;

     @Schema(description =  "文件名称")
    private String fileName;

     @Schema(description =  "文件类型")
    private String mimeType;

     @Schema(description =  "文件大小")
    private int size;

     @Schema(description =  "图片宽度")
    private int width;

     @Schema(description =  "图片高度")
    private int height;
}
