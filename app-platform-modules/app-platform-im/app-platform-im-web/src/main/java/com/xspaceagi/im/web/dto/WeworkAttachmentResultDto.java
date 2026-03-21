package com.xspaceagi.im.web.dto;

import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 企业微信附件下载结果：成功上传的附件 + 不支持的附件 key 列表。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WeworkAttachmentResultDto {

    private List<AttachmentDto> attachments = new ArrayList<>();

    private List<String> unsupportedKeys = new ArrayList<>();

}
