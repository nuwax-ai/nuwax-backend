package com.xspaceagi.agent.core.application.service;

import com.xspaceagi.agent.core.adapter.repository.entity.SkillConfig;
import com.xspaceagi.agent.core.domain.service.ConfigHistoryDomainService;
import com.xspaceagi.agent.core.domain.service.SkillDomainService;
import com.xspaceagi.file.application.service.FileManagementService;
import com.xspaceagi.file.domain.model.FileRecordDomain;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

        when(skillDomainService.add(any(SkillConfig.class))).thenReturn(42L);
        when(fileManagementService.uploadFile(
                any(), anyLong(), anyLong(), anyString(), anyLong(), anyString(), anyBoolean()))
                .thenReturn(FileRecordDomain.builder().fileUrl("mock://uploaded").build());
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
            String entryName) throws Exception {
        long result = service.importSkill(
                zipFile(entryNameCharset, entryName),
                null,
                100L,
                null);

        assertEquals(42L, result);
        assertPersistedPath(entryName);
    }

    private static Stream<Arguments> zipEntryNameCases() {
        return Stream.of(
                Arguments.of("ASCII entry name", StandardCharsets.US_ASCII, "references/example.txt"),
                Arguments.of("UTF-8 Chinese nested path", StandardCharsets.UTF_8, "资料/说明.txt"),
                Arguments.of("GBK Chinese nested path", Charset.forName("GBK"), "复测资料/终审样本.txt"));
    }

    private void assertPersistedPath(String expectedPath) {
        ArgumentCaptor<SkillConfig> updateCaptor = ArgumentCaptor.forClass(SkillConfig.class);
        verify(skillDomainService).update(updateCaptor.capture());

        List<String> storedPaths = updateCaptor.getValue().getFiles().stream()
                .map(file -> file.getName())
                .toList();
        assertTrue(storedPaths.contains(expectedPath), () -> "Stored paths were " + storedPaths);
    }

    private static MockMultipartFile zipFile(Charset entryNameCharset, String entryName) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, entryNameCharset)) {
            writeEntry(zip, "SKILL.md", SKILL_MD);
            writeEntry(zip, entryName, "test");
        }
        return new MockMultipartFile("file", "skill.zip", "application/zip", output.toByteArray());
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
