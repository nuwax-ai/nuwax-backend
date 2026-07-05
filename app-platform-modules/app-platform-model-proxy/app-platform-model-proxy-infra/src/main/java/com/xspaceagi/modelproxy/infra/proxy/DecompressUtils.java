package com.xspaceagi.modelproxy.infra.proxy;

import lombok.extern.slf4j.Slf4j;
import org.brotli.dec.BrotliInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

@Slf4j
public class DecompressUtils {

    /**
     * 根据 Content-Encoding 自动解压
     *
     * @param data     原始字节
     * @param encoding Content-Encoding 值：gzip, br, deflate, null
     */
    public static byte[] decompress(byte[] data, String encoding) throws Exception {
        try {
            if (encoding == null || encoding.isEmpty()) {
                return data;
            }
            return switch (encoding.toLowerCase()) {
                case "gzip", "x-gzip" -> decompressGzip(data);
                case "br" -> decompressBrotli(data);
                case "deflate" -> decompressDeflate(data);
                default -> data;
            };
        } catch (Throwable e) {
            log.error("Error decompressing data", e);
            return data;
        }
    }

    private static byte[] decompressGzip(byte[] compressed) throws Exception {
        try (InputStream is = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        }
    }

    private static byte[] decompressBrotli(byte[] compressed) throws Exception {
        try (InputStream bis = new BrotliInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = bis.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        }
    }

    private static byte[] decompressDeflate(byte[] compressed) throws Exception {
        try (InputStream is = new java.util.zip.InflaterInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        }
    }
}