package com.xspaceagi.knowledge.domain.knowledgeimageanalysis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * 文档图片解析服务: 根据文件类型路由到合适的解析器。
 *
 * <ul>
 *   <li>{@code .docx} -> {@link ImageAwareDocumentParser#parseDocx}(POI, 按段落顺序)</li>
 *   <li>{@code .pdf}  -> {@link ImageAwareDocumentParser#parsePdf}(PDFBox, 仅实际绘制的图片, 按 Y 定位)</li>
 *   <li>其它         -> {@link TikaImageAwareParser}(Tika 通用兜底)</li>
 * </ul>
 *
 * <p>构造函数接收 {@code ObjectProvider<DocumentImageUploader>} 以便:
 * <ul>
 *   <li>在 Spring 容器中注入默认上传实现;</li>
 *   <li>在单元测试中手动构造(mock provider)。</li>
 * </ul>
 */
@Slf4j
@Service
public class DocumentImageParseService {

    private final ObjectProvider<DocumentImageUploader> uploaderProvider;
    private final TikaImageAwareParser tikaParser = new TikaImageAwareParser();
    private final ImageAwareDocumentParser imageAwareParser = new ImageAwareDocumentParser();

    public DocumentImageParseService(ObjectProvider<DocumentImageUploader> uploaderProvider) {
        this.uploaderProvider = uploaderProvider;
    }

    /**
     * 按文件名路由解析, 使用指定的上传回调。
     */
    public DocumentImageParseResult parse(InputStream in, String fileName,
                                          ImageUploadCallback callback) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        int q = lower.indexOf('?');
        if (q > 0) {
            lower = lower.substring(0, q);
        }
        if (lower.endsWith(".docx")) {
            log.info("使用 POI 解析 DOCX, fileName={}", fileName);
            return imageAwareParser.parseDocx(in, callback);
        }
        if (lower.endsWith(".pdf")) {
            log.info("使用 PDFBox 解析 PDF, fileName={}", fileName);
            return imageAwareParser.parsePdf(in, callback);
        }
        log.info("使用 Tika 通用解析, fileName={}", fileName);
        return tikaParser.parse(in, callback);
    }

    /**
     * 按文件名路由解析, 使用容器中默认的上传实现。
     */
    public DocumentImageParseResult parse(InputStream in, String fileName) {
        ImageUploadCallback callback = null;
        if (uploaderProvider != null) {
            DocumentImageUploader uploader = uploaderProvider.getIfAvailable();
            if (uploader != null) {
                callback = uploader::upload;
            }
        }
        return parse(in, fileName, callback);
    }

    /**
     * 不带文件名的通用解析(走 Tika)。
     */
    public DocumentImageParseResult parse(InputStream in) {
        return tikaParser.parse(in, null);
    }
}
