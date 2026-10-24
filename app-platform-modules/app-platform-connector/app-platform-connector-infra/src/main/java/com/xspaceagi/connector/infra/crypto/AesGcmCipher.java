package com.xspaceagi.connector.infra.crypto;

import com.xspaceagi.connector.spec.ConnectorConstants;
import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.connector.spec.exception.ConnectorException;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 凭证加密器（设计文档 §4.2、§10 对照：修正 open-connector scrypt 固定 salt 弱点）。
 * <p>
 * 密文格式：{@code enc:v1:{salt}:{iv}:{tag}:{data}}，四段均为无填充 Base64URL：
 * <ul>
 *   <li>salt：每条记录独立 16 字节随机值；记录密钥 = SHA-256(masterKey || salt)</li>
 *   <li>iv：12 字节随机值</li>
 *   <li>tag：GCM 128 位认证标签</li>
 *   <li>data：密文体</li>
 * </ul>
 * salt 同时作为 GCM AAD 绑定，防止密文段重排。
 */
@Component
public class AesGcmCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_LENGTH = TAG_BITS / 8;

    private final SecureRandom secureRandom = new SecureRandom();

    @Resource
    private ConnectorKeyProvider keyProvider;

    public AesGcmCipher() {
    }

    /**
     * 供单元测试注入自定义 key provider。
     */
    public AesGcmCipher(ConnectorKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    /**
     * 加密明文，产出 {@code enc:v1:} 前缀密文。
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new ConnectorException(ConnectorErrorCode.VALIDATION_ERROR, "加密明文不能为空");
        }
        try {
            byte[] master = keyProvider.requireMasterKey();
            byte[] salt = new byte[SALT_LENGTH];
            secureRandom.nextBytes(salt);
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(deriveKey(master, salt), "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(salt);
            byte[] ctAndTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] data = new byte[ctAndTag.length - TAG_LENGTH];
            byte[] tag = new byte[TAG_LENGTH];
            System.arraycopy(ctAndTag, 0, data, 0, data.length);
            System.arraycopy(ctAndTag, data.length, tag, 0, TAG_LENGTH);

            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return ConnectorConstants.CIPHER_PREFIX
                    + encoder.encodeToString(salt) + ":"
                    + encoder.encodeToString(iv) + ":"
                    + encoder.encodeToString(tag) + ":"
                    + encoder.encodeToString(data);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorException(ConnectorErrorCode.DECRYPT_FAILED, "凭证加密失败", e);
        }
    }

    /**
     * 解密 {@code enc:v1:} 密文；标签校验失败（篡改/错密钥）抛 {@link ConnectorErrorCode#DECRYPT_FAILED}。
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || !ciphertext.startsWith(ConnectorConstants.CIPHER_PREFIX)) {
            throw new ConnectorException(ConnectorErrorCode.CIPHERTEXT_INVALID);
        }
        String body = ciphertext.substring(ConnectorConstants.CIPHER_PREFIX.length());
        // limit=-1：保留尾部空段（空明文加密时 data 段为空串）
        String[] parts = body.split(":", -1);
        if (parts.length != 4) {
            throw new ConnectorException(ConnectorErrorCode.CIPHERTEXT_INVALID);
        }
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] salt = decoder.decode(parts[0]);
            byte[] iv = decoder.decode(parts[1]);
            byte[] tag = decoder.decode(parts[2]);
            byte[] data = decoder.decode(parts[3]);
            if (tag.length != TAG_LENGTH) {
                throw new ConnectorException(ConnectorErrorCode.CIPHERTEXT_INVALID);
            }

            byte[] master = keyProvider.requireMasterKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(deriveKey(master, salt), "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(salt);
            byte[] ctAndTag = new byte[data.length + TAG_LENGTH];
            System.arraycopy(data, 0, ctAndTag, 0, data.length);
            System.arraycopy(tag, 0, ctAndTag, data.length, TAG_LENGTH);
            return new String(cipher.doFinal(ctAndTag), StandardCharsets.UTF_8);
        } catch (ConnectorException e) {
            throw e;
        } catch (AEADBadTagException e) {
            throw new ConnectorException(ConnectorErrorCode.DECRYPT_FAILED, "凭证解密失败（密文被篡改或密钥不匹配）", e);
        } catch (IllegalArgumentException e) {
            throw new ConnectorException(ConnectorErrorCode.CIPHERTEXT_INVALID, e.getMessage(), e);
        } catch (Exception e) {
            throw new ConnectorException(ConnectorErrorCode.DECRYPT_FAILED, "凭证解密失败", e);
        }
    }

    /**
     * 记录密钥 = SHA-256(masterKey || salt)。主密钥为高熵随机值，SHA-256 作为 KDF 足够。
     */
    private byte[] deriveKey(byte[] master, byte[] salt) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(master);
        digest.update(salt);
        return digest.digest();
    }
}
