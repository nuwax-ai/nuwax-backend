package com.xspaceagi.connector.infra.crypto;

import com.xspaceagi.connector.spec.ConnectorConstants;
import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.connector.spec.exception.ConnectorException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3 验收单测：加解密回环 / 篡改 tag 报错 / 错 key 报错 / 前缀与格式校验。
 */
class AesGcmCipherTest {

    private static final String KEY_A = Base64.getEncoder().encodeToString(repeat((byte) 0x11, 32));
    private static final String KEY_B = Base64.getEncoder().encodeToString(repeat((byte) 0x22, 32));

    private AesGcmCipher cipher;

    @BeforeEach
    void setUp() {
        System.setProperty(ConnectorConstants.ENCRYPTION_KEY_ENV, KEY_A);
        cipher = new AesGcmCipher(new ConnectorKeyProvider());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(ConnectorConstants.ENCRYPTION_KEY_ENV);
    }

    @Test
    void roundTrip() {
        String plain = "{\"accessToken\":\"gho_xxx\",\"refreshToken\":\"r-yyy\",\"scope\":[\"repo\",\"gist\"]}";
        String ciphertext = cipher.encrypt(plain);
        assertTrue(ciphertext.startsWith(ConnectorConstants.CIPHER_PREFIX));
        assertEquals(4, ciphertext.substring(ConnectorConstants.CIPHER_PREFIX.length()).split(":").length);
        assertEquals(plain, cipher.decrypt(ciphertext));
    }

    @Test
    void roundTripUnicodeAndEmpty() {
        assertEquals("", cipher.decrypt(cipher.encrypt("")));
        String unicode = "飞书凭证-🔑-{\"k\":\"中文值\"}";
        assertEquals(unicode, cipher.decrypt(cipher.encrypt(unicode)));
    }

    @Test
    void eachEncryptionUsesFreshSaltAndIv() {
        String c1 = cipher.encrypt("same-plaintext");
        String c2 = cipher.encrypt("same-plaintext");
        assertNotEquals(c1, c2);
        assertEquals(cipher.decrypt(c1), cipher.decrypt(c2));
    }

    @Test
    void tamperedTagFails() {
        String ciphertext = cipher.encrypt("{\"apiKey\":\"secret\"}");
        String[] parts = ciphertext.substring(ConnectorConstants.CIPHER_PREFIX.length()).split(":");
        // 翻转 tag 首个字符
        char first = parts[2].charAt(0);
        parts[2] = (first == 'A' ? 'B' : 'A') + parts[2].substring(1);
        String tampered = ConnectorConstants.CIPHER_PREFIX + String.join(":", parts);
        ConnectorException ex = assertThrows(ConnectorException.class, () -> cipher.decrypt(tampered));
        assertEquals(ConnectorErrorCode.DECRYPT_FAILED, ex.getErrorCode());
    }

    @Test
    void tamperedDataFails() {
        String ciphertext = cipher.encrypt("{\"apiKey\":\"secret\"}");
        String[] parts = ciphertext.substring(ConnectorConstants.CIPHER_PREFIX.length()).split(":");
        byte[] data = Base64.getUrlDecoder().decode(parts[3]);
        data[0] ^= 0x01;
        parts[3] = Base64.getUrlEncoder().withoutPadding().encodeToString(data);
        String tampered = ConnectorConstants.CIPHER_PREFIX + String.join(":", parts);
        ConnectorException ex = assertThrows(ConnectorException.class, () -> cipher.decrypt(tampered));
        assertEquals(ConnectorErrorCode.DECRYPT_FAILED, ex.getErrorCode());
    }

    @Test
    void wrongKeyFails() {
        String ciphertext = cipher.encrypt("{\"apiKey\":\"secret\"}");
        System.setProperty(ConnectorConstants.ENCRYPTION_KEY_ENV, KEY_B);
        AesGcmCipher otherCipher = new AesGcmCipher(new ConnectorKeyProvider());
        ConnectorException ex = assertThrows(ConnectorException.class, () -> otherCipher.decrypt(ciphertext));
        assertEquals(ConnectorErrorCode.DECRYPT_FAILED, ex.getErrorCode());
    }

    @Test
    void invalidFormatRejected() {
        assertThrows(ConnectorException.class, () -> cipher.decrypt(null));
        assertThrows(ConnectorException.class, () -> cipher.decrypt("plain-text"));
        assertThrows(ConnectorException.class, () -> cipher.decrypt(ConnectorConstants.CIPHER_PREFIX + "a:b"));
        assertThrows(ConnectorException.class,
                () -> cipher.decrypt(ConnectorConstants.CIPHER_PREFIX + "a:b:c:not-base64!!!"));
    }

    @Test
    void missingKeyRejected() {
        System.clearProperty(ConnectorConstants.ENCRYPTION_KEY_ENV);
        AesGcmCipher noKeyCipher = new AesGcmCipher(new ConnectorKeyProvider());
        ConnectorException ex = assertThrows(ConnectorException.class, () -> noKeyCipher.encrypt("x"));
        assertEquals(ConnectorErrorCode.KEY_NOT_CONFIGURED, ex.getErrorCode());
    }

    @Test
    void hexKeyAccepted() {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            hex.append('a');
        }
        System.setProperty(ConnectorConstants.ENCRYPTION_KEY_ENV, hex.toString());
        AesGcmCipher hexCipher = new AesGcmCipher(new ConnectorKeyProvider());
        assertEquals("ok", hexCipher.decrypt(hexCipher.encrypt("ok")));
    }

    @Test
    void wrongLengthKeyRejected() {
        System.setProperty(ConnectorConstants.ENCRYPTION_KEY_ENV,
                Base64.getEncoder().encodeToString(repeat((byte) 0x33, 16)));
        AesGcmCipher shortKeyCipher = new AesGcmCipher(new ConnectorKeyProvider());
        ConnectorException ex = assertThrows(ConnectorException.class, () -> shortKeyCipher.encrypt("x"));
        assertEquals(ConnectorErrorCode.KEY_NOT_CONFIGURED, ex.getErrorCode());
    }

    private static byte[] repeat(byte value, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = value;
        }
        return bytes;
    }
}
