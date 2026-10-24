package com.xspaceagi.knowledge.domain.knowledgeimageanalysis;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 支持图片提取的文档解析器。
 *
 * <p><b>PDF 关键修复</b>: PDF 中每一页的 resource 字典常常列出文档中的<b>全部</b>图片资源,
 * 但页面内容流只通过 {@code Do} 算子<b>绘制</b>其中的若干张。如果直接遍历
 * {@code page.getResources().getXObjectNames()} 会导致同一批图片在每一页被重复计入
 * (例如 4 张图片 * 4 页 = 16 个 Markdown 地址)。
 *
 * <p>本解析器通过自定义 {@link PDFTextStripper} 处理内容流, <b>仅在遇到 {@code Do}
 * 算子且其引用的是图片 XObject 时</b>才记录该图片, 从而精确统计<b>实际绘制</b>的图片数量,
 * 并根据图片在页面上的纵坐标(Y)把 Markdown 图片标记插入到正文对应位置。
 *
 * <p><b>DOCX</b> 通过 POI 按文档阅读顺序遍历段落与内嵌图片, 图片在其所在段落位置输出。
 */
@Slf4j
public class ImageAwareDocumentParser {

    /**
     * 解析 PDF, 提取文本与实际绘制的图片。
     *
     * @param in       PDF 输入流
     * @param callback 图片上传回调, 为 null 时仅提取文本不产出图片标记
     * @return 解析结果
     */
    public DocumentImageParseResult parsePdf(InputStream in, ImageUploadCallback callback) {
        try {
            byte[] data = readAll(in);
            StringBuilder content = new StringBuilder();
            List<DocumentImageParseResult.ImageInfo> images = new ArrayList<>();
            int globalIndex = 0;

            try (PDDocument doc = Loader.loadPDF(data)) {
                for (int p = 0; p < doc.getNumberOfPages(); p++) {
                    // 每页一个全新的收集器, 确保只处理当前页
                    PdfPageCollector collector = new PdfPageCollector();
                    collector.setSortByPosition(true);
                    collector.setStartPage(p + 1);
                    collector.setEndPage(p + 1);
                    collector.getText(doc);

                    // 按 Y(自上而下) 排序, 使序号与正文出现顺序一致
                    collector.imageEvents.sort(
                            Comparator.comparingDouble(e -> (double) e.yTop));

                    // 上传本页实际绘制的图片
                    for (ImageEvent ie : collector.imageEvents) {
                        globalIndex++;
                        String fileName = "image_" + globalIndex + "." + ie.suffix;
                        ie.url = callback == null ? null
                                : callback.upload(ie.bytes, fileName, ie.contentType);
                        images.add(DocumentImageParseResult.ImageInfo.builder()
                                .index(globalIndex)
                                .fileName(fileName)
                                .size(ie.bytes.length)
                                .uploadedUrl(ie.url)
                                .build());
                    }

                    // 将文本行与图片按 Y 合并, 自上而下输出
                    List<PageEvent> events = new ArrayList<>();
                    for (TextEvent te : collector.textEvents) {
                        events.add(new PageEvent(te.yTop, te));
                    }
                    for (ImageEvent ie : collector.imageEvents) {
                        events.add(new PageEvent(ie.yTop, ie));
                    }
                    events.sort(Comparator.comparingDouble(e -> (double) e.yTop));

                    for (PageEvent ev : events) {
                        if (ev.image != null) {
                            if (ev.image.url != null) {
                                content.append(MarkdownImageFormatter.format(ev.image.url))
                                        .append('\n');
                            }
                        } else if (ev.text.text != null && !ev.text.text.isEmpty()) {
                            content.append(ev.text.text).append('\n');
                        }
                    }
                    content.append('\n');
                }
            }

            return DocumentImageParseResult.builder()
                    .content(content.toString())
                    .totalImages(images.size())
                    .images(images)
                    .success(true)
                    .build();
        } catch (Exception e) {
            log.error("PDF 解析失败", e);
            return DocumentImageParseResult.builder()
                    .success(false)
                    .errorMessage("PDF 解析失败: " + e.getMessage())
                    .images(new ArrayList<>())
                    .build();
        }
    }

    /**
     * 解析 DOCX, 按阅读顺序提取文本与内嵌图片。
     */
    public DocumentImageParseResult parseDocx(InputStream in, ImageUploadCallback callback) {
        try (XWPFDocument doc = new XWPFDocument(in)) {
            StringBuilder content = new StringBuilder();
            List<DocumentImageParseResult.ImageInfo> images = new ArrayList<>();
            int globalIndex = 0;

            for (IBodyElement element : doc.getBodyElements()) {
                if (!(element instanceof XWPFParagraph paragraph)) {
                    continue;
                }
                List<XWPFPicture> pictures = new ArrayList<>();
                for (XWPFRun run : paragraph.getRuns()) {
                    if (run.getEmbeddedPictures() != null) {
                        pictures.addAll(run.getEmbeddedPictures());
                    }
                }

                String text = paragraph.getText();
                if (text != null && !text.isEmpty()) {
                    content.append(text).append('\n');
                }
                // 段落内嵌图片按出现顺序追加在该段文本之后
                for (XWPFPicture picture : pictures) {
                    byte[] data = picture.getPictureData() == null ? null
                            : picture.getPictureData().getData();
                    if (data == null) {
                        continue;
                    }
                    globalIndex++;
                    String ext = picture.getPictureData().suggestFileExtension();
                    String suffix = (ext == null || ext.isEmpty()) ? "png" : ext;
                    String contentType = picture.getPictureData().getPackagePart() == null
                            ? "image/" + suffix
                            : picture.getPictureData().getPackagePart().getContentType();
                    String fileName = "image_" + globalIndex + "." + suffix;
                    String url = callback == null ? null
                            : callback.upload(data, fileName, contentType);
                    images.add(DocumentImageParseResult.ImageInfo.builder()
                            .index(globalIndex)
                            .fileName(fileName)
                            .size(data.length)
                            .uploadedUrl(url)
                            .build());
                    if (url != null) {
                        content.append(MarkdownImageFormatter.format(url)).append('\n');
                    }
                }
            }

            return DocumentImageParseResult.builder()
                    .content(content.toString())
                    .totalImages(images.size())
                    .images(images)
                    .success(true)
                    .build();
        } catch (Exception e) {
            log.error("DOCX 解析失败", e);
            return DocumentImageParseResult.builder()
                    .success(false)
                    .errorMessage("DOCX 解析失败: " + e.getMessage())
                    .images(new ArrayList<>())
                    .build();
        }
    }

    /**
     * 根据文件名后缀路由到 PDF / DOCX 解析器。
     */
    public DocumentImageParseResult parse(byte[] data, String fileName,
                                          ImageUploadCallback callback) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        int q = lower.indexOf('?');
        if (q > 0) {
            lower = lower.substring(0, q);
        }
        if (lower.endsWith(".docx")) {
            return parseDocx(new java.io.ByteArrayInputStream(data), callback);
        }
        if (lower.endsWith(".pdf")) {
            return parsePdf(new java.io.ByteArrayInputStream(data), callback);
        }
        return DocumentImageParseResult.builder()
                .success(false)
                .errorMessage("不支持的文件类型: " + fileName)
                .images(new ArrayList<>())
                .build();
    }

    // ======================== 内部数据结构 ========================

    /** 文本行事件 */
    private static final class TextEvent {
        final float yTop;
        final String text;

        TextEvent(float yTop, String text) {
            this.yTop = yTop;
            this.text = text;
        }
    }

    /** 图片事件 */
    private static final class ImageEvent {
        final float yTop;
        final byte[] bytes;
        final String suffix;
        final String contentType;
        String url;

        ImageEvent(float yTop, byte[] bytes, String suffix, String contentType) {
            this.yTop = yTop;
            this.bytes = bytes;
            this.suffix = suffix;
            this.contentType = contentType;
        }
    }

    /** 页面合并事件 */
    private static final class PageEvent {
        final float yTop;
        final TextEvent text;
        final ImageEvent image;

        PageEvent(float yTop, TextEvent text) {
            this.yTop = yTop;
            this.text = text;
            this.image = null;
        }

        PageEvent(float yTop, ImageEvent image) {
            this.yTop = yTop;
            this.text = null;
            this.image = image;
        }
    }

    // ======================== PDF 收集器 ========================

    /**
     * 自定义 {@link PDFTextStripper}, 在抽取文本的同时记录:
     * <ul>
     *   <li>每个文本行的顶部 Y 坐标(自上而下)</li>
     *   <li>每个<b>实际绘制</b>的图片及其纵向中心 Y 坐标</li>
     * </ul>
     */
    private static final class PdfPageCollector extends PDFTextStripper {

        final List<TextEvent> textEvents = new ArrayList<>();
        final List<ImageEvent> imageEvents = new ArrayList<>();
        private float pageUpperRightY;

        @Override
        public void processPage(PDPage page) throws IOException {
            PDRectangle crop = page.getCropBox();
            pageUpperRightY = crop.getUpperRightY();
            super.processPage(page);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions)
                throws IOException {
            if (textPositions != null && !textPositions.isEmpty()) {
                float yTop = Float.MAX_VALUE;
                for (TextPosition tp : textPositions) {
                    yTop = Math.min(yTop, tp.getYDirAdj());
                }
                textEvents.add(new TextEvent(yTop, text));
            }
            super.writeString(text, textPositions);
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands)
                throws IOException {
            if ("Do".equals(operator.getName()) && operands != null && !operands.isEmpty()) {
                COSName name = (COSName) operands.get(0);
                PDXObject xobject = getResources().getXObject(name);
                if (xobject instanceof PDImageXObject) {
                    captureImage((PDImageXObject) xobject);
                }
            }
            super.processOperator(operator, operands);
        }

        /**
         * 解码兜底路径的单张图片像素内存上限(宽×高×4字节):
         * 超过此值的 FlateDecode 等格式图片跳过不解码,防止解码本身导致OOM。
         * 10MB ≈ 1581×1581 正方形图——覆盖截图(1080p=8MB)、图表、logo 等有价值的
         * FlateDecode 图片;仅挡住 3000×2000+ 的超大图(像素24MB+PNG编码≈72MB峰值)。
         * 最坏并发: 4路×10MB像素×3倍PNG编码=120MB,2GB堆内安全。
         */
        private static final long MAX_DECODE_PIXELS_BYTES = 10L * 1024 * 1024;

        /**
         * 记录一张实际绘制的图片, 以其纵向中心(自上而下)作为排序 Y。
         *
         * <p><b>原始字节直拷</b>: JPEG(DCTDecode)/JPEG2000(JPXDecode) 图片在 PDF 内的
         * 编码字节流本身就是完整的图片文件, 直接拷贝原始流即可——不做像素解码
         * (解码一张扫描页需 宽×高×4 字节瞬时内存, 实测使单本书堆占用放大至
         * 文件体积的100倍以上)也不再重编码为 PNG(照片类内容 PNG 比 JPEG 大 3-10 倍)。
         * JP2 虽然主流浏览器暂不渲染(image/jp2),但数据保留在S3中可下载/程序可读,
         * 优于彻底丢失;前端可后续加降级展示(如显示下载链接)。
         * 仅 FlateDecode 等其他滤镜的图片走解码兜底(有像素内存上限)。
         */
        private void captureImage(PDImageXObject image) throws IOException {
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            float centerY = pageUpperRightY - ctm.getTranslateY()
                    - ctm.getScalingFactorY() / 2.0f;
            try {
                String rawSuffix = rawImageSuffix(image);
                if (rawSuffix != null) {
                    org.apache.pdfbox.cos.COSStream cosStream = (org.apache.pdfbox.cos.COSStream) image.getCOSObject();
                    try (InputStream raw = cosStream.createRawInputStream();
                         ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                        raw.transferTo(bos);
                        byte[] bytes = bos.toByteArray();
                        if (bytes.length > 0) {
                            String contentType = "jp2".equals(rawSuffix) ? "image/jp2" : "image/jpeg";
                            imageEvents.add(new ImageEvent(centerY, bytes, rawSuffix, contentType));
                        }
                    }
                    return;
                }
                // 解码前检查像素内存: 宽×高×4字节(ARGB)超上限则跳过
                long pixelBytes = (long) image.getWidth() * image.getHeight() * 4;
                if (pixelBytes > MAX_DECODE_PIXELS_BYTES) {
                    log.warn("图片像素内存超限({}MB), 跳过解码: {}x{}",
                            pixelBytes / 1048576, image.getWidth(), image.getHeight());
                    return;
                }
                BufferedImage buffered = image.getImage();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                if (!ImageIO.write(buffered, "png", bos)) {
                    throw new IOException("ImageIO 无法写入 PNG");
                }
                imageEvents.add(new ImageEvent(centerY, bos.toByteArray(), "png", "image/png"));
            } catch (Exception e) {
                log.warn("图片解码失败, 跳过: {}", e.getMessage());
            }
        }

        /**
         * 图片滤镜为可直接拷贝的压缩格式(JPEG/JPEG2000)时返回对应后缀, 否则 null(需解码)。
         */
        private static String rawImageSuffix(PDImageXObject image) {
            // PDImageXObject 3.x 无 getFilters(), 经 PDStream 获取; COSDictionary 侧同理
            org.apache.pdfbox.pdmodel.common.PDStream pdStream = new org.apache.pdfbox.pdmodel.common.PDStream(
                    (org.apache.pdfbox.cos.COSStream) image.getCOSObject());
            List<COSName> filters = pdStream.getFilters();
            if (filters == null || filters.size() != 1) {
                return null;
            }
            COSName filter = filters.get(0);
            if (COSName.DCT_DECODE.equals(filter) || COSName.DCT_DECODE_ABBREVIATION.equals(filter)) {
                return "jpg";
            }
            if (COSName.JPX_DECODE.equals(filter)) {
                // JPEG2000直拷保留数据(主流浏览器暂不渲染image/jp2,markdown中会显示裂图;
                // 但数据在S3中可下载/程序可读,优于彻底丢失;前端可后续加降级展示)
                return "jp2";
            }
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            bos.write(buffer, 0, n);
        }
        return bos.toByteArray();
    }
}
