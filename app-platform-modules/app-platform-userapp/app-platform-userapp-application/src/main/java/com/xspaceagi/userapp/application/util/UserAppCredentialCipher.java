package com.xspaceagi.userapp.application.util;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;

/**
 * 数据库凭证加解密（AES/GCM）。
 */
@Component
public class UserAppCredentialCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;

    private final SecretKey key;

    private final SecureRandom secureRandom = new SecureRandom();

    public UserAppCredentialCipher(@Value("${userapp.credential.aes-key:}") String base64Key) {
        if (StringUtils.isBlank(base64Key)) {
            this.key = null;
            return;
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("userapp.credential.aes-key is not valid Base64", e);
        }
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalStateException("userapp.credential.aes-key must be 16/24/32 bytes after Base64 decoding");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /** 明文 → Base64(iv + 密文) */
    public String encrypt(String plainText) {
        requireKey();
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw credentialError(e);
        }
    }

    /** Base64(iv + 密文) → 明文 */
    public String decrypt(String encoded) {
        if (StringUtils.isBlank(encoded)) {
            return null;
        }
        requireKey();
        try {
            byte[] data = Base64.getDecoder().decode(encoded);
            byte[] iv = Arrays.copyOfRange(data, 0, IV_LENGTH_BYTES);
            byte[] cipherText = Arrays.copyOfRange(data, IV_LENGTH_BYTES, data.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw credentialError(e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("userapp.credential.aes-key is not configured");
        }
    }

    private BizException credentialError(Exception e) {
        return BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.userappProjectCredentialSaveFailed, e.getMessage());
    }
}
