package com.xspaceagi.im.wechat.ilink;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-128-ECB PKCS7 加解密（对齐 openclaw-weixin src/cdn/aes-ecb.ts）
 */
public final class WechatIlinkAesEcb {

    private WechatIlinkAesEcb() {
    }

    public static byte[] encrypt(byte[] plaintext, byte[] key16) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(normalizeKey(key16), "AES"));
        return cipher.doFinal(plaintext);
    }

    public static byte[] decrypt(byte[] ciphertext, byte[] key16) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(normalizeKey(key16), "AES"));
        return cipher.doFinal(ciphertext);
    }

    private static byte[] normalizeKey(byte[] key) {
        if (key.length == 16) {
            return key;
        }
        return Arrays.copyOf(key, 16);
    }

    /**
     * 与插件 parseAesKey 一致：base64 → 16 字节 或 32 字符 hex。
     */
    public static byte[] parseAesKeyFromBase64(String aesKeyBase64) {
        byte[] decoded = Base64.getDecoder().decode(aesKeyBase64);
        if (decoded.length == 16) {
            return decoded;
        }
        String ascii = new String(decoded, StandardCharsets.US_ASCII);
        if (decoded.length == 32 && ascii.matches("^[0-9a-fA-F]{32}$")) {
            return hexToBytes(ascii);
        }
        throw new IllegalArgumentException("aes_key must decode to 16 raw bytes or 32-char hex string");
    }

    private static byte[] hexToBytes(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static int aesEcbPaddedSize(int plaintextSize) {
        return (int) (Math.ceil((plaintextSize + 1) / 16.0) * 16);
    }

    public static String md5Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
