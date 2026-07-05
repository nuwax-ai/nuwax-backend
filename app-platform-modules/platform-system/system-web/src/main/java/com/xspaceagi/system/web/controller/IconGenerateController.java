package com.xspaceagi.system.web.controller;

import cn.hutool.core.codec.Base64;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.HttpStatusEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.web.emoj.IconGenerator;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@Tag(name = "文件上传")
@Slf4j
@RestController
public class IconGenerateController {

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

    @Value("${file.uploadFolder:}")
    private String uploadFolder;

    @Resource
    private IFileAccessService iFileAccessService;

    private final IconGenerator iconGenerator = new IconGenerator();


    @GetMapping("/api/file/**")
    public void downloadFile(HttpServletRequest request, HttpServletResponse response) {
        if (!RequestContext.get().isLogin()) {
            try {
                iFileAccessService.checkFileUrlAk(request.getRequestURI(), request.getParameter("ak"));
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
                if (contentType == null && key.toLowerCase().endsWith(".svg")) {
                    contentType = "image/svg+xml";
                }
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
        String text = URLDecoder.decode(key, StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BufferedImage image = iconGenerator.generateIcon(text, 200);
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    @GetMapping(path = "/api/logo/{type}/**", produces = "image/png")
    public byte[] defaultLogo0(@PathVariable String type, HttpServletRequest request) throws IOException {
        String key = request.getRequestURI().substring(("/api/logo/" + type).length());
        String text = URLDecoder.decode(key, StandardCharsets.UTF_8);
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
