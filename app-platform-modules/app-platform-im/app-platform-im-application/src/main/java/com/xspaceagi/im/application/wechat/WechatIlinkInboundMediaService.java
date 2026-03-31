package com.xspaceagi.im.application.wechat;

import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.im.application.dto.ImChannelConfigDto;
import com.xspaceagi.im.wechat.ilink.IlinkConstants;
import com.xspaceagi.im.wechat.ilink.WechatIlinkCdnDownloader;
import com.xspaceagi.im.wechat.ilink.dto.CdnMedia;
import com.xspaceagi.im.wechat.ilink.dto.FileItem;
import com.xspaceagi.im.wechat.ilink.dto.ImageItem;
import com.xspaceagi.im.wechat.ilink.dto.MessageItem;
import com.xspaceagi.im.wechat.ilink.dto.VideoItem;
import com.xspaceagi.im.wechat.ilink.dto.VoiceItem;
import com.xspaceagi.im.wechat.ilink.dto.WeixinMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 入站媒体：CDN 下载 + AES 解密后上传存储，填充 {@link AttachmentDto} 供智能体使用。
 */
@Slf4j
@Service
public class WechatIlinkInboundMediaService {

    @Autowired(required = false)
    private WechatIlinkAttachmentUploader attachmentUploader;

    public List<AttachmentDto> buildAttachmentsFromMessage(WeixinMessage msg, ImChannelConfigDto channelDto) {
        if (msg == null || msg.getItemList() == null || msg.getItemList().isEmpty()) {
            return Collections.emptyList();
        }
        if (attachmentUploader == null) {
            log.debug("wechat ilink WechatIlinkAttachmentUploader absent, skip inbound attachments");
            return Collections.emptyList();
        }
        String cdnBase = IlinkConstants.CDN_BASE_URL;
        if (channelDto != null && channelDto.getWechatIlink() != null
                && StringUtils.isNotBlank(channelDto.getWechatIlink().getCdnBaseUrl())) {
            cdnBase = channelDto.getWechatIlink().getCdnBaseUrl();
        }
        Long tenantId = channelDto != null ? channelDto.getTenantId() : null;

        List<AttachmentDto> out = new ArrayList<>();
        for (MessageItem it : msg.getItemList()) {
            if (it == null || it.getType() == null) {
                continue;
            }
            try {
                switch (it.getType()) {
                    case 2 -> {
                        byte[] raw = downloadImage(it.getImageItem(), cdnBase);
                        addOne(out, raw, guessImageFileName(it.getImageItem()), guessImageMime(raw), tenantId);
                    }
                    case 3 -> addOne(out, downloadVoice(it.getVoiceItem(), cdnBase), "voice.sil", "audio/silk", tenantId);
                    case 4 -> addOne(out, downloadFile(it.getFileItem(), cdnBase), fileNameOrDefault(it.getFileItem()), null, tenantId);
                    case 5 -> addOne(out, downloadVideo(it.getVideoItem(), cdnBase), "video.mp4", "video/mp4", tenantId);
                    default -> {
                    }
                }
            } catch (Exception e) {
                log.warn("wechat ilink inbound media item skipped, type={}, mid={}", it.getType(), msg.getMessageId(), e);
            }
        }
        return out;
    }

    private void addOne(List<AttachmentDto> out, byte[] bytes, String filename, String mimeOverride, Long tenantId) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        String mime = mimeOverride;
        if (StringUtils.isBlank(mime) && filename != null) {
            String f = filename.toLowerCase();
            if (f.endsWith(".jpg") || f.endsWith(".jpeg")) {
                mime = "image/jpeg";
            } else if (f.endsWith(".png")) {
                mime = "image/png";
            } else if (f.endsWith(".gif")) {
                mime = "image/gif";
            } else if (f.endsWith(".mp4")) {
                mime = "video/mp4";
            } else if (f.endsWith(".amr")) {
                mime = "audio/amr";
            }
        }
        if (StringUtils.isBlank(mime)) {
            mime = "application/octet-stream";
        }
        String fn = StringUtils.isNotBlank(filename) ? filename : "file.bin";
        AttachmentDto dto = attachmentUploader.upload(bytes, fn, mime, tenantId);
        if (dto != null) {
            out.add(dto);
        }
    }

    private static String guessImageMime(byte[] b) {
        if (b == null || b.length < 3) {
            return "image/jpeg";
        }
        if (b[0] == (byte) 0xFF && b[1] == (byte) 0xD8) {
            return "image/jpeg";
        }
        if (b.length >= 8 && b[0] == (byte) 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            return "image/gif";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private static String fileNameOrDefault(FileItem fileItem) {
        if (fileItem == null) {
            return "file.bin";
        }
        if (StringUtils.isNotBlank(fileItem.getFileName())) {
            return fileItem.getFileName();
        }
        return "file.bin";
    }

    private static String guessImageFileName(ImageItem img) {
        if (img == null) {
            return "image.jpg";
        }
        return "image.jpg";
    }

    private static byte[] downloadImage(ImageItem img, String cdnBase) throws Exception {
        if (img == null) {
            return null;
        }
        CdnMedia media = firstNonBlankMedia(img.getMedia(), img.getThumbMedia());
        return decryptMedia(media, cdnBase);
    }

    private static byte[] downloadVoice(VoiceItem voice, String cdnBase) throws Exception {
        if (voice == null || voice.getMedia() == null) {
            return null;
        }
        return decryptMedia(voice.getMedia(), cdnBase);
    }

    private static byte[] downloadFile(FileItem file, String cdnBase) throws Exception {
        if (file == null || file.getMedia() == null) {
            return null;
        }
        return decryptMedia(file.getMedia(), cdnBase);
    }

    private static byte[] downloadVideo(VideoItem video, String cdnBase) throws Exception {
        if (video == null || video.getMedia() == null) {
            return null;
        }
        return decryptMedia(video.getMedia(), cdnBase);
    }

    private static CdnMedia firstNonBlankMedia(CdnMedia primary, CdnMedia fallback) {
        if (primary != null && StringUtils.isNotBlank(primary.getEncryptQueryParam()) && StringUtils.isNotBlank(primary.getAesKey())) {
            return primary;
        }
        if (fallback != null && StringUtils.isNotBlank(fallback.getEncryptQueryParam()) && StringUtils.isNotBlank(fallback.getAesKey())) {
            return fallback;
        }
        return primary != null ? primary : fallback;
    }

    private static byte[] decryptMedia(CdnMedia media, String cdnBase) throws Exception {
        if (media == null || StringUtils.isBlank(media.getEncryptQueryParam()) || StringUtils.isBlank(media.getAesKey())) {
            return null;
        }
        return WechatIlinkCdnDownloader.downloadAndDecrypt(media.getEncryptQueryParam(), media.getAesKey(), cdnBase);
    }
}
