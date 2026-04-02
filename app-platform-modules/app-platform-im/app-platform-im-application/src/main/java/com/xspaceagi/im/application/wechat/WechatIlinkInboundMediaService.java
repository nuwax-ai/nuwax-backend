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
import com.xspaceagi.im.wechat.ilink.WechatIlinkMessageHelper;
import com.xspaceagi.im.application.util.MimeTypeUtils;
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
    private WechatIlinkAttachmentService attachmentUploader;

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
                    case WechatIlinkMessageHelper.ITEM_IMAGE -> {
                        byte[] raw = downloadImage(it.getImageItem(), cdnBase);
                        addOne(out, raw, null, null, tenantId);
                    }
                    case WechatIlinkMessageHelper.ITEM_VOICE -> {
                        byte[] voiceBytes = downloadVoice(it.getVoiceItem(), cdnBase);
                        String voiceMime = inferVoiceMimeType(it.getVoiceItem());
                        if (isSilkVoice(it.getVoiceItem())) {
                            byte[] wavBytes = WechatIlinkSilkTranscoder.tryConvertSilkToWav(voiceBytes);
                            if (wavBytes != null && wavBytes.length > 0) {
                                voiceBytes = wavBytes;
                                voiceMime = "audio/wav";
                            }
                        }
                        addOne(out, voiceBytes, null, voiceMime, tenantId);
                    }
                    case WechatIlinkMessageHelper.ITEM_FILE -> {
                        byte[] fileBytes = downloadFile(it.getFileItem(), cdnBase);
                        String fileName = it.getFileItem() != null ? it.getFileItem().getFileName() : null;
                        String mime = MimeTypeUtils.inferMimeTypeFromFileName(fileName);
                        addOne(out, fileBytes, fileName, mime, tenantId);
                    }
                    case WechatIlinkMessageHelper.ITEM_VIDEO -> addOne(out, downloadVideo(it.getVideoItem(), cdnBase), null, "video/mp4", tenantId);
                    default -> {
                        // ITEM_TEXT 或未知类型，不处理附件
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
        AttachmentDto dto = attachmentUploader.upload(bytes, filename, mimeOverride, tenantId);
        if (dto != null) {
            out.add(dto);
        }
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

    private static String inferVoiceMimeType(VoiceItem voice) {
        if (voice == null || voice.getEncodeType() == null) {
            return "audio/silk";
        }
        return switch (voice.getEncodeType()) {
            case 1 -> "audio/L16";
            case 4 -> "audio/speex";
            case 5 -> "audio/amr";
            case 6 -> "audio/silk";
            case 7 -> "audio/mpeg";
            case 8 -> "audio/ogg";
            default -> "audio/silk";
        };
    }

    private static boolean isSilkVoice(VoiceItem voice) {
        return voice != null && voice.getEncodeType() != null && voice.getEncodeType() == 6;
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
