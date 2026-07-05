package com.xspaceagi.agent.core.spec.utils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.*;

/**
 * ZIP 字符串压缩/解压工具
 */
public class ZipStringUtils {

    /**
     * 压缩字符串为 byte 数组
     */
    public static byte[] compress(String input) throws IOException {
        if (input == null || input.isEmpty()) {
            return new byte[0];
        }
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("data"));
            zos.write(input.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            return bos.toByteArray();
        }
    }

    /**
     * 压缩字符串为 Base64 字符串（方便传输/存储）
     */
    public static String compressToBase64(String input) throws IOException {
        return Base64.getEncoder().encodeToString(compress(input));
    }

    /**
     * 从 byte 数组解压为字符串
     */
    public static String decompress(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            return "";
        }
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             ZipInputStream zis = new ZipInputStream(bis)) {
            zis.getNextEntry();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = zis.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            zis.closeEntry();
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * 从 Base64 字符串解压为原始字符串
     */
    public static String decompressFromBase64(String base64) throws IOException {
        return decompress(Base64.getDecoder().decode(base64));
    }
}