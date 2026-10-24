package com.xspaceagi.knowledge.domain.knowledgeimageanalysis;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tika 嵌入文档提取器: 在解析过程中捕获图片二进制并通过回调上传。
 *
 * <p>对相同内容(SHA-256)的图片做去重, 避免同一资源被重复计入,
 * 这是从根源上避免 "图片 Markdown 地址重复" 的关键之一。
 */
@Slf4j
public class TikaImageExtractingExtractor extends ParsingEmbeddedDocumentExtractor {

    /**
     * 单张图片字节上限(20MB): 超过则跳过不上传。
     * 图片以整个 byte[] 形式驻留堆内,批量解析大文件时若不设限,
     * 单张超大图即可撑爆堆(扫描件/恶意构造的 PDF 是主要来源)
     */
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;

    private final ImageUploadCallback uploadCallback;
    private final List<DocumentImageParseResult.ImageInfo> images = new ArrayList<>();
    private final Set<String> seenHashes = new HashSet<>();
    private int imageCounter;

    public TikaImageExtractingExtractor(ParseContext context, ImageUploadCallback uploadCallback) {
        super(context);
        this.uploadCallback = uploadCallback;
    }

    public List<DocumentImageParseResult.ImageInfo> getImages() {
        return images;
    }

    @Override
    public boolean shouldParseEmbedded(Metadata metadata) {
        return true;
    }

    @Override
    public void parseEmbedded(InputStream stream, ContentHandler handler,
                              Metadata metadata, boolean outputHtml)
            throws SAXException, IOException {
        String contentType = metadata.get(Metadata.CONTENT_TYPE);
        boolean isImage = contentType != null
                && contentType.toLowerCase().startsWith("image/");

        // 非图片或无上传回调时, 走默认行为(不产出图片)
        if (!isImage || uploadCallback == null) {
            super.parseEmbedded(stream, handler, metadata, outputHtml);
            return;
        }

        byte[] bytes = readUpTo(stream, MAX_IMAGE_BYTES);
        if (bytes == null) {
            log.warn("Skip oversized embedded image (> {} bytes), contentType={}", MAX_IMAGE_BYTES, contentType);
            return;
        }
        String hash = sha256(bytes);
        // 内容去重: 相同字节的图片只记录一次
        if (seenHashes.contains(hash)) {
            return;
        }
        seenHashes.add(hash);

        imageCounter++;
        String suffix = suffixOf(contentType);
        String fileName = "image_" + imageCounter + "." + suffix;
        String url = uploadCallback.upload(bytes, fileName, contentType);
        images.add(DocumentImageParseResult.ImageInfo.builder()
                .index(imageCounter)
                .fileName(fileName)
                .size(bytes.length)
                .uploadedUrl(url)
                .build());
    }

    /**
     * 有界读取: 流超过 limit 字节返回 null(调用方跳过),避免超大图片整体读入堆内
     */
    private static byte[] readUpTo(InputStream stream, int limit) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        int total = 0;
        int n;
        while ((n = stream.read(chunk)) != -1) {
            total += n;
            if (total > limit) {
                return null;
            }
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    private static String suffixOf(String contentType) {
        if (contentType == null) {
            return "png";
        }
        String c = contentType.toLowerCase();
        if (c.contains("png")) return "png";
        if (c.contains("jpeg") || c.contains("jpg")) return "jpg";
        if (c.contains("gif")) return "gif";
        if (c.contains("bmp")) return "bmp";
        if (c.contains("tiff")) return "tiff";
        return "png";
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toString(java.util.Arrays.hashCode(data));
        }
    }
}
