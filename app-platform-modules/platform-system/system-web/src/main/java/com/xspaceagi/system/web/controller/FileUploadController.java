package com.xspaceagi.system.web.controller;

import cn.hutool.core.codec.Base64;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.sdk.service.UserAccessKeyApiService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.HttpStatusEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.FileAkUtil;
import com.xspaceagi.system.web.dto.UploadResultDto;
import com.xspaceagi.system.web.emoj.IconGenerator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "文件上传")
@Slf4j
@RestController
@Profile("!local")
public class FileUploadController {

    private static final Color[] rgbs = new Color[]{
            new Color(117, 189, 108),
            new Color(77, 87, 224),
            new Color(63, 118, 247),
            new Color(238, 193, 79),
            new Color(160, 109, 237)
    };

    private static final Map<String, Color> componentColorMap = Map.of(
            "workflow", new Color(96, 183, 93),
            "plugin", new Color(75, 64, 222),
            "knowledge", new Color(228, 148, 51),
            "table", new Color(255, 187, 0)
    );

    @Value("${cos.baseUrl}")
    private String baseUrl;

    @Value("${cos.secretId}")
    private String secretId;

    @Value("${cos.secretKey}")
    private String secretKey;

    @Value("${file.uploadFolder:}")
    private String uploadFolder;

    @Value("${file.baseUrl:}")
    private String fileBaseUrl;

    @Value("${storage.type}")
    private String storageType;

    @Value("${cos.regionName:ap-chengdu}")
    private String regionName;

    @Value("${cos.bucketName:agent-1251073634}")
    private String bucketName;

    @Resource
    private FileAkUtil fileUrl;

    @Resource
    private UserAccessKeyApiService userAccessKeyApiService;

    private IconGenerator iconGenerator = new IconGenerator();

    @Operation(summary = "文件上传接口", description = "文件上传接口，返回文件网络地址")
    @PostMapping("/api/file/upload")
    public ReqResult<UploadResultDto> fileUpload(@RequestParam("file") MultipartFile file,
                                                 @Schema(description = "存储类型，tmp 临时文件；store 永久存储")
                                                 @RequestParam(name = "type", required = false, defaultValue = "store") String type,
                                                 HttpServletRequest request) {
        if (!RequestContext.get().isLogin()) {
            ChatKeyCheck.check(request, userAccessKeyApiService);
        }
        if (file.isEmpty()) {
            return ReqResult.error("Please select a file to upload");
        }
        try {
            // 获取文件的后缀
            String fileExtension = "";
            String originalFilename = file.getOriginalFilename();
            int i = originalFilename.lastIndexOf('.');
            if (i > 0) {
                fileExtension = originalFilename.substring(i + 1);
            }

            // 生成新的文件名
            String newFileName = type + "/" + UUID.randomUUID().toString().replace("-", "") + "." + fileExtension;
            // 获取文件字节
            byte[] bytes = file.getBytes();
            String url = "";
            if (storageType.equals("file")) {
                //支持的文件类型包括  PDF、TXT、DOC、DOCX、MD、JSON、XML、XLS、XLSX、PPT、PPTX、MP4、MP3、ZIP、RAR、7Z、TAR、GZ、BZ2、TGZ、TAR.GZ、TAR.BZ2、TAR.7Z、TAR.GZ2、TAR.BZ、TAR.BZ2、TAR.7Z、TAR.G、JPG、JPE、JPEG、PNG、GIF、BMP、ICO、ICNS、ICO、ICO、ICO、ICO、ICO、ICO、ICO、ICO、ICO、ICO、ICO、WEBP、SVG
                List<String> fileTypes = List.of("pdf", "txt", "doc", "docx", "md", "json", "xml", "xls", "xlsx", "ppt", "pptx", "mp4", "mov", "mp3", "wav", "aac", "flac", "ogg", "wma", "aiff", "m4a", "amr", "midi", "opus", "ra", "zip", "rar", "7z", "tar", "gz", "bz2", "tgz", "tar.gz", "tar.bz2", "tar.7z", "tar.gz", "jpg", "jpeg", "jpe", "png", "gif", "bmp", "ico", "icns", "svg", "webp", "heic", "mkv", "webm");
                if (fileExtension != null && !fileTypes.contains(fileExtension.toLowerCase())) {
                    return ReqResult.error("不支持的文件类型");
                }
                String path0 = uploadFolder.endsWith("/") ? uploadFolder + type : uploadFolder + "/" + type;
                // 创建目录
                Path dirPath = Paths.get(path0);
                if (!Files.exists(dirPath)) {
                    Files.createDirectories(dirPath);
                }
                TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
                String fileBaseUrl = tenantConfigDto.getSiteUrl();
                if (StringUtils.isBlank(fileBaseUrl)) {
                    fileBaseUrl = this.fileBaseUrl;
                } else {
                    fileBaseUrl = fileBaseUrl.trim().endsWith("/") ? fileBaseUrl.trim() : fileBaseUrl.trim() + "/";
                    fileBaseUrl = fileBaseUrl + "api/file/";
                }
                url = fileBaseUrl + newFileName;
                // 创建文件路径
                Path path = Paths.get(uploadFolder.endsWith("/") ? uploadFolder + newFileName : uploadFolder + "/" + newFileName);
                // 保存文件到路径
                Files.write(path, bytes);
            } else {
                url = baseUrl + newFileName;
                COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);
                Region region = new Region(regionName);
                ClientConfig clientConfig = new ClientConfig(region);
                clientConfig.setHttpProtocol(HttpProtocol.https);
                COSClient cosClient = new COSClient(cred, clientConfig);
                ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentLength(bytes.length);
                metadata.setContentType(file.getContentType());
                cosClient.putObject(bucketName, newFileName, file.getInputStream(), metadata);
            }
            if (!RequestContext.get().isLogin()) {
                url = fileUrl.getFileUrlWithAk(url);
            }
            UploadResultDto uploadResultDto = new UploadResultDto();
            uploadResultDto.setFileName(originalFilename);
            uploadResultDto.setKey(newFileName);
            uploadResultDto.setUrl(url);
            uploadResultDto.setMimeType(file.getContentType());
            uploadResultDto.setSize(bytes.length);
            return ReqResult.success(uploadResultDto);
        } catch (IOException e) {
            log.error("File upload failed", e);
        }
        return ReqResult.error("File upload failed");
    }

    @GetMapping("/api/file/**")
    public void downloadFile(HttpServletRequest request, HttpServletResponse response) {
        if (!RequestContext.get().isLogin()) {
            try {
                fileUrl.checkFileUrlAk(request.getRequestURI(), request.getParameter("ak"));
            } catch (Exception e) {
                throw BizException.of(HttpStatusEnum.UNAUTHORIZED, ErrorCodeEnum.UNAUTHORIZED,
                        BizExceptionCodeEnum.systemUnauthorizedOrSessionExpired);
            }
        }
        try {
            String key = request.getRequestURI().substring("/api/file/".length());
            String path0 = uploadFolder.endsWith("/") ? uploadFolder + key : uploadFolder + "/" + key;
            Path path = Paths.get(path0);
            if (Files.exists(path)) {
                //根据key的后缀设置contentType
                String contentType = Files.probeContentType(path);
                response.setContentType(contentType);
                Files.copy(path, response.getOutputStream());
            }
        } catch (IOException e) {
            log.error("文件下载失败", e);
        }
    }

    @GetMapping(path = "/api/logo/**", produces = "image/png")
    public byte[] defaultLogo(HttpServletRequest request) throws IOException {
        String key = request.getRequestURI().substring("/api/logo/".length());
        String text = URLDecoder.decode(key, "UTF-8");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BufferedImage image = iconGenerator.generateIcon(text, 200);
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    @GetMapping(path = "/api/logo/{type}/**", produces = "image/png")
    public byte[] defaultLogo0(@PathVariable String type, HttpServletRequest request) throws IOException {
        String key = request.getRequestURI().substring(("/api/logo/" + type).length());
        String text = URLDecoder.decode(key, "UTF-8");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BufferedImage image = iconGenerator.generateIcon(text, 200);
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    @GetMapping("/api/qr/{base64text}")
    public void generateQrCode(@PathVariable("base64text") String base64text, HttpServletResponse response) {
        byte[] decode = Base64.decode(base64text);
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(new String(decode), BarcodeFormat.QR_CODE, 300, 300);
            MatrixToImageWriter.writeToStream(matrix, "PNG", response.getOutputStream());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
