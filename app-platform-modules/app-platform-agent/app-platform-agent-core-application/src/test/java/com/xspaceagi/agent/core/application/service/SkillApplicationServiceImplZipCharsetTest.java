package com.xspaceagi.agent.core.application.service;

import com.xspaceagi.agent.core.adapter.repository.entity.SkillConfig;
import com.xspaceagi.agent.core.domain.service.ConfigHistoryDomainService;
import com.xspaceagi.agent.core.domain.service.SkillDomainService;
import com.xspaceagi.file.application.service.FileManagementService;
import com.xspaceagi.file.domain.model.FileRecordDomain;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillApplicationServiceImplZipCharsetTest {

    private static final String SKILL_MD = """
            ---
            name: zip-import-test
            description: ZIP import test
            ---
            # ZIP Import Test
            """;

    @Mock
    private SkillDomainService skillDomainService;

    @Mock
    private FileManagementService fileManagementService;

    @Mock
    private ConfigHistoryDomainService configHistoryDomainService;

    @InjectMocks
    private SkillApplicationServiceImpl service;

    @BeforeEach
    void setUp() {
        RequestContext.set(RequestContext.builder()
                .userId(7L)
                .tenantId(8L)
                .userContext(UserContext.builder()
                        .userId(7L)
                        .tenantId(8L)
                        .userName("tester")
                        .build())
                .build());
    }

    @AfterEach
    void tearDown() {
        RequestContext.remove();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("zipEntryNameCases")
    void importsZipEntryNamesWithoutChangingThePath(
            String caseName,
            Charset entryNameCharset,
            String entryName,
            boolean clearUtf8Flag) throws Exception {
        stubSuccessfulImport();

        long result = service.importSkill(
                zipFile(entryNameCharset, entryName, clearUtf8Flag),
                null,
                100L,
                null);

        assertEquals(42L, result);
        assertPersistedPath(entryName);
    }

    private static Stream<Arguments> zipEntryNameCases() {
        return Stream.of(
                Arguments.of("ASCII entry name", StandardCharsets.US_ASCII, "references/example.txt", false),
                Arguments.of("UTF-8 Chinese nested path", StandardCharsets.UTF_8, "资料/说明.txt", false),
                Arguments.of("GBK Chinese nested path", Charset.forName("GBK"), "复测资料/终审样本.txt", false),
                Arguments.of("unflagged UTF-8 Chinese nested path", StandardCharsets.UTF_8,
                        "中文目录/使用说明.txt", true));
    }

    @Test
    void rejectsCorruptZipWithoutTreatingItAsAnEncodingFallback() throws Exception {
        byte[] corruptZip = zipFile(
                StandardCharsets.UTF_8,
                "references/example.txt",
                false).getBytes();
        setSecondLocalEntryCompressionMethod(corruptZip, 99);

        BizException error = assertThrows(BizException.class, () -> service.importSkill(
                new MockMultipartFile("file", "corrupt.zip", "application/zip", corruptZip),
                null,
                100L,
                null));

        assertFalse(error.getMessage().contains("malformed input"), error::getMessage);
    }

    private void stubSuccessfulImport() {
        when(skillDomainService.add(any(SkillConfig.class))).thenReturn(42L);
        when(fileManagementService.uploadFile(
                any(), anyLong(), anyLong(), anyString(), anyLong(), anyString(), anyBoolean()))
                .thenReturn(FileRecordDomain.builder().fileUrl("mock://uploaded").build());
    }

    private void assertPersistedPath(String expectedPath) {
        ArgumentCaptor<SkillConfig> updateCaptor = ArgumentCaptor.forClass(SkillConfig.class);
        verify(skillDomainService).update(updateCaptor.capture());

        List<String> storedPaths = updateCaptor.getValue().getFiles().stream()
                .map(file -> file.getName())
                .toList();
        assertTrue(storedPaths.contains(expectedPath), () -> "Stored paths were " + storedPaths);
    }

    private static MockMultipartFile zipFile(
            Charset entryNameCharset,
            String entryName,
            boolean clearUtf8Flag) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, entryNameCharset)) {
            writeEntry(zip, "SKILL.md", SKILL_MD);
            writeEntry(zip, entryName, "test");
        }
        byte[] zipBytes = output.toByteArray();
        if (clearUtf8Flag) {
            clearEfsFlags(zipBytes);
        }
        return new MockMultipartFile("file", "skill.zip", "application/zip", zipBytes);
    }

    private static void clearEfsFlags(byte[] zipBytes) {
        for (int offset = 0; offset <= zipBytes.length - 10; offset++) {
            if (hasSignature(zipBytes, offset, 0x04034b50)) {
                clearEfsFlag(zipBytes, offset + 6);
            } else if (hasSignature(zipBytes, offset, 0x02014b50)) {
                clearEfsFlag(zipBytes, offset + 8);
            }
        }
    }

    private static boolean hasSignature(byte[] bytes, int offset, int signature) {
        return (bytes[offset] & 0xff) == (signature & 0xff)
                && (bytes[offset + 1] & 0xff) == ((signature >>> 8) & 0xff)
                && (bytes[offset + 2] & 0xff) == ((signature >>> 16) & 0xff)
                && (bytes[offset + 3] & 0xff) == ((signature >>> 24) & 0xff);
    }

    private static void clearEfsFlag(byte[] bytes, int flagOffset) {
        int flags = (bytes[flagOffset] & 0xff) | ((bytes[flagOffset + 1] & 0xff) << 8);
        flags &= ~0x0800;
        bytes[flagOffset] = (byte) flags;
        bytes[flagOffset + 1] = (byte) (flags >>> 8);
    }

    private static void setSecondLocalEntryCompressionMethod(byte[] bytes, int method) {
        int localEntry = 0;
        for (int offset = 0; offset <= bytes.length - 10; offset++) {
            if (!hasSignature(bytes, offset, 0x04034b50)) {
                continue;
            }
            localEntry++;
            if (localEntry == 2) {
                bytes[offset + 8] = (byte) method;
                bytes[offset + 9] = (byte) (method >>> 8);
                return;
            }
        }
        throw new IllegalArgumentException("ZIP did not contain a second local entry");
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
