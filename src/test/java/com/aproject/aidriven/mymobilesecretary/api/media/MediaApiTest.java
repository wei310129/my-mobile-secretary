package com.aproject.aidriven.mymobilesecretary.api.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

class MediaApiTest extends IntegrationTestBase {

    private static final byte[] PNG = new byte[] {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3
    };

    @Autowired private ObjectMapper objectMapper;

    @Test
    void appUploadCanBeListedAndDownloadedThroughPrivateApi() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.png", "image/png", PNG);

        String body = mockMvc.perform(multipart("/api/media")
                        .file(file).param("displayName", "七月收據"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("七月收據"))
                .andExpect(jsonPath("$.mediaKind").value("IMAGE"))
                .andExpect(jsonPath("$.contentUrl").isNotEmpty())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode saved = objectMapper.readTree(body);
        long id = saved.path("id").asLong();

        mockMvc.perform(get("/api/media"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(id)).exists());
        byte[] downloaded = mockMvc.perform(get("/api/media/{id}/content", id))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader(
                        "X-Content-Type-Options")).isEqualTo("nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(PNG);

        mockMvc.perform(delete("/api/media/{id}", id))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/media/{id}/content", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void officeDocumentIsStoredButAnalyzeEndpointDoesNotOpenIt() throws Exception {
        byte[] opaqueZip = new byte[] {0x50, 0x4b, 0x03, 0x04, 1, 2, 3, 4};
        MockMultipartFile file = new MockMultipartFile(
                "file", "handover.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                opaqueZip);

        mockMvc.perform(multipart("/api/media/analyze").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.media.mediaKind").value("DOCUMENT"))
                .andExpect(jsonPath("$.action").value("DOCUMENT_STORED_UNINTERPRETED"));
    }

    @Test
    void fakeImageContentIsRejectedEvenWhenFilenameAndHeaderSayPng() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "malware.png", "image/png", "not png".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/media").file(file))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }
}
