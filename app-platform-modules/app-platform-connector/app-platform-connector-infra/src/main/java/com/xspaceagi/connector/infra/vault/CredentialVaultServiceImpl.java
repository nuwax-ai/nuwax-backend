package com.xspaceagi.connector.infra.vault;

import com.xspaceagi.connector.domain.service.CredentialVaultService;
import com.xspaceagi.connector.infra.crypto.AesGcmCipher;
import com.xspaceagi.connector.domain.entity.ConnectorCredential;
import com.xspaceagi.connector.infra.repository.ConnectorCredentialRepository;
import com.xspaceagi.connector.spec.error.ConnectorErrorCode;
import com.xspaceagi.connector.spec.exception.ConnectorException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 凭证保险库实现：AES-256-GCM 加密落库，明文只在方法栈内短暂存在。
 */
@Service
public class CredentialVaultServiceImpl implements CredentialVaultService {

    @Resource
    private ConnectorCredentialRepository credentialRepository;

    @Resource
    private AesGcmCipher cipher;

    @Override
    public long store(String credType, String plainJson, Date expiresAt) {
        String ciphertext = cipher.encrypt(plainJson);
        return credentialRepository.insert(credType, ciphertext, expiresAt);
    }

    @Override
    public String reveal(long credentialId) {
        ConnectorCredential credential = require(credentialId);
        return cipher.decrypt(credential.getCiphertext());
    }

    @Override
    public Meta peek(long credentialId) {
        ConnectorCredential credential = require(credentialId);
        return new Meta(credential.getId(), credential.getCredType(),
                credential.getExpiresAt(), credential.getRevision());
    }

    @Override
    public boolean writeBack(long credentialId, String plainJson, Date expiresAt, long expectedRevision) {
        String ciphertext = cipher.encrypt(plainJson);
        return credentialRepository.updateWithRevision(credentialId, ciphertext, expiresAt, expectedRevision);
    }

    @Override
    public void delete(long credentialId) {
        credentialRepository.deleteById(credentialId);
    }

    private ConnectorCredential require(long credentialId) {
        ConnectorCredential credential = credentialRepository.getById(credentialId);
        if (credential == null) {
            throw new ConnectorException(ConnectorErrorCode.CREDENTIAL_NOT_FOUND);
        }
        return credential;
    }
}
