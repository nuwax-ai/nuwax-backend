package com.xspaceagi.interceptor;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AES加解密工具类
 * 支持多种加密模式和填充方式
 *
 * @author Nuwax
 */
public class AESCrypto {

    // 加密算法
    private static final String ALGORITHM = "AES";

    // 默认使用AES-256-CBC模式
    private static final String DEFAULT_TRANSFORMATION = "AES/CBC/PKCS5Padding";

    /**
     * AES加密（支持自定义模式）
     *
     * @param plainText     明文
     * @param key           密钥
     * @param iv            初始化向量（ECB模式可为null）
     * @param transformation 转换模式（如：AES/CBC/PKCS5Padding）
     * @return Base64编码的密文
     */
    public static String encrypt(String plainText, String key, String iv, String transformation) throws Exception {
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        Cipher cipher = Cipher.getInstance(transformation);

        // 判断是否需要IV
        if (transformation.contains("ECB")) {
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        } else {
            IvParameterSpec ivSpec = new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8));
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);
        }

        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    /**
     * AES解密（支持自定义模式）
     *
     * @param cipherText    Base64编码的密文
     * @param key           密钥
     * @param iv            初始化向量（ECB模式可为null）
     * @param transformation 转换模式
     * @return 解密后的明文
     */
    public static String decrypt(String cipherText, String key, String iv, String transformation) throws Exception {
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        Cipher cipher = Cipher.getInstance(transformation);

        // 判断是否需要IV
        if (transformation.contains("ECB")) {
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
        } else {
            IvParameterSpec ivSpec = new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8));
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);
        }

        byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(cipherText));
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    /**
     * AES解密（使用Hex密钥）
     *
     * @param cipherText Base64编码的密文
     * @param keyHex     十六进制密钥
     * @param ivHex      十六进制IV
     * @return 解密后的明文
     */
    public static String decryptWithHexKey(String cipherText, String keyHex, String ivHex) throws Exception {
        byte[] keyBytes = hexStringToByteArray(keyHex);
        byte[] ivBytes = hexStringToByteArray(ivHex);

        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
        Cipher cipher = Cipher.getInstance(DEFAULT_TRANSFORMATION);
        IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);

        byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(cipherText));
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    /**
     * 十六进制字符串转字节数组
     */
    private static byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
