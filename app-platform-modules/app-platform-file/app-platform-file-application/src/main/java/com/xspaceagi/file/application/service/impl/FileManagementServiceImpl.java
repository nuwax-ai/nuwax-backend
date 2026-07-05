package com.xspaceagi.file.application.service.impl;

import com.xspaceagi.file.application.service.FileManagementService;
import com.xspaceagi.file.domain.model.FileRecordDomain;
import com.xspaceagi.file.domain.repository.FileRecordRepository;
import com.xspaceagi.file.domain.storage.FileStorageStrategy;
import com.xspaceagi.file.infra.storage.FileKeyGenerator;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * File Management Service Implementation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileManagementServiceImpl implements FileManagementService {

    private final FileRecordRepository fileRecordRepository;
    private final Map<String, FileStorageStrategy> storageStrategyMap;
    private final TenantConfigApplicationService tenantConfigApplicationService;

    @Value("${storage.type:file}")
    private String defaultStorageType;

    private FileStorageStrategy getStorageStrategy() {
        if ("file".equals(defaultStorageType)){
            defaultStorageType = "local";
        }
        return storageStrategyMap.values().stream()
                .filter(strategy -> strategy.getStorageType().equals(defaultStorageType))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Storage strategy not found: " + defaultStorageType));
    }

    @Override
    public FileRecordDomain uploadFile(MultipartFile file, Long tenantId, Long userId,
                                       String targetType, Long targetId, String metadata, boolean isAuthRequired) {

        FileStorageStrategy strategy = getStorageStrategy();

        // Upload file, pass targetType for fileKey generation
        String fileKey;
        try {
            fileKey = strategy.upload(file.getInputStream(), file.getOriginalFilename(),
                    file.getContentType(), targetType);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Get fileBaseUrl from tenant config (async IM callbacks may have no RequestContext)
        RequestContext<?> requestContext = RequestContext.get();
        TenantConfigDto tenantConfigDto = requestContext != null
                ? (TenantConfigDto) requestContext.getTenantConfig()
                : null;
        if (tenantConfigDto == null && tenantId != null) {
            tenantConfigDto = tenantConfigApplicationService.getTenantConfig(tenantId);
        }
        String fileBaseUrl = tenantConfigDto != null ? tenantConfigDto.getSiteUrl() : "http://localhost:8081";

        // Generate fileUrl: fileBaseUrl/api/f/fileKey
        String fileUrl = fileBaseUrl + "/api/f/" + fileKey;

        // Get file extension
        String fileName = file.getOriginalFilename();
        String fileExtension = getFileExtension(fileName);

        // Save file record
        FileRecordDomain fileRecord = FileRecordDomain.builder()
                .tenantId(tenantId)
                .userId(userId)
                .targetType(targetType)
                .targetId(targetId)
                .fileName(fileName)
                .fileSize(file.getSize())
                .fileType(file.getContentType())
                .fileExtension(fileExtension)
                .metadata(metadata)
                .fileKey(fileKey)
                .storageType(strategy.getStorageType())
                .fileUrl(fileUrl)
                .authRequired(isAuthRequired)
                .status("active")
                .created(new Date())
                .modified(new Date())
                .build();

        return fileRecordRepository.save(fileRecord);

    }

    private static String getFileExtension(String fileName) {
        String ext = FileKeyGenerator.extractExtension(fileName);
        return ext.startsWith(".") ? ext.substring(1) : ext;
    }

    @Override
    public InputStream downloadFile(String fileKey) {
        FileRecordDomain fileRecord = fileRecordRepository.findByFileKey(fileKey);
        if (fileRecord == null) {
            throw new RuntimeException("File not found");
        }

        FileStorageStrategy strategy = storageStrategyMap.values().stream()
                .filter(s -> s.getStorageType().equals(fileRecord.getStorageType()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Storage strategy not found"));

        return strategy.download(fileKey);
    }

    @Override
    public boolean deleteFile(Long fileId) {
        FileRecordDomain fileRecord = fileRecordRepository.findById(fileId);
        if (fileRecord == null) {
            return false;
        }

        // Delete file from storage
        FileStorageStrategy strategy = storageStrategyMap.values().stream()
                .filter(s -> s.getStorageType().equals(fileRecord.getStorageType()))
                .findFirst()
                .orElse(null);

        if (strategy != null) {
            strategy.delete(fileRecord.getFileKey());
        }

        // Physically delete database record
        return fileRecordRepository.deleteById(fileId);
    }

    @Override
    public boolean batchDeleteFiles(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return false;
        }
        boolean allSuccess = true;
        for (Long fileId : fileIds) {
            if (fileId == null) {
                allSuccess = false;
                continue;
            }
            boolean deleted = deleteFile(fileId);
            if (!deleted) {
                allSuccess = false;
            }
        }
        return allSuccess;
    }

    @Override
    public FileRecordDomain getFileById(Long fileId) {
        return fileRecordRepository.findById(fileId);
    }

    @Override
    public FileRecordDomain getFileByKey(String fileKey) {
        return TenantFunctions.callWithIgnoreCheck(() -> fileRecordRepository.findByFileKey(fileKey));
    }

    @Override
    public List<FileRecordDomain> listUserFiles(Long tenantId, Long userId) {
        return fileRecordRepository.findByTenantIdAndUserId(tenantId, userId);
    }

    @Override
    public List<FileRecordDomain> listTargetFiles(Long tenantId, String targetType, Long targetId) {
        return fileRecordRepository.findByTarget(tenantId, targetType, targetId);
    }

    @Override
    public String getFileUrl(String fileKey) {
        FileRecordDomain fileRecord = fileRecordRepository.findByFileKey(fileKey);
        if (fileRecord == null) {
            throw new RuntimeException("File not found");
        }
        return fileRecord.getFileUrl();
    }

    @Override
    public String generatePresignedUrl(String fileKey, int expireSeconds) {
        FileRecordDomain fileRecord = fileRecordRepository.findByFileKey(fileKey);
        if (fileRecord == null) {
            throw new RuntimeException("File not found");
        }

        FileStorageStrategy strategy = storageStrategyMap.values().stream()
                .filter(s -> s.getStorageType().equals(fileRecord.getStorageType()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Storage strategy not found"));

        return strategy.generatePresignedUrl(fileKey, expireSeconds);
    }

    @Override
    public String generatePresignedUrlByType(String fileKey, String storageType, int expireSeconds, Integer download) {
        FileStorageStrategy strategy = storageStrategyMap.values().stream()
                .filter(s -> s.getStorageType().equals(storageType))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Storage strategy not found: " + storageType));

        // If download is requested, query database for original filename
        if (download != null && download == 1) {
            FileRecordDomain fileRecord = TenantFunctions.callWithIgnoreCheck(() -> fileRecordRepository.findByFileKey(fileKey));
            if (fileRecord != null && fileRecord.getFileName() != null) {
                return strategy.generatePresignedUrl(fileKey, expireSeconds, fileRecord.getFileName());
            }
        }

        return strategy.generatePresignedUrl(fileKey, expireSeconds);
    }
}
