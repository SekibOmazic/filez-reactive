package io.filemanager.filez.controller;

import io.filemanager.filez.database.FileMetadata;
import io.filemanager.filez.service.DownloadResult;
import io.filemanager.filez.service.S3Service;
import io.filemanager.filez.service.zipped.StreamingZipService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebFluxTest(FileController.class) // Focus only on the FileController
class FileControllerTest {

    @Autowired
    private WebTestClient webTestClient; // A client for testing web endpoints without a real server

    @MockitoBean
    private S3Service s3Service; // We mock the service layer

    @MockitoBean
    private StreamingZipService streamingZipService; // Also mock other dependencies

    @Test
    @DisplayName("POST /upload should call service and return 200 OK with metadata")
    void uploadFile_success() {
        // --- Arrange ---
        // 1. Mock the service response
        FileMetadata mockMetadata = new FileMetadata(1L, "response.txt", "text/plain", 100L);
        when(s3Service.uploadFile(any())).thenReturn(Mono.just(mockMetadata));

        // 2. Build a multipart request
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource("test content".getBytes()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "form-data; name=file; filename=upload.txt");

        // --- Act & Assert ---
        webTestClient.post().uri("/api/files/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(FileMetadata.class)
                .isEqualTo(mockMetadata);
    }

    @Test
    @DisplayName("GET /download/{id} should return file stream when found")
    void downloadFileById_whenFound_returnsFile() {
        // --- Arrange ---
        // 1. Mock the service response
        String fileContent = "This is the file content!";
        byte[] fileBytes = fileContent.getBytes(StandardCharsets.UTF_8);
        DownloadResult mockResult = new DownloadResult(
                "download.txt",
                "text/plain",
                Flux.just(ByteBuffer.wrap(fileBytes))
        );
        when(s3Service.downloadFile(any(Long.class))).thenReturn(Mono.just(mockResult));

        // --- Act & Assert ---
        webTestClient.get().uri("/api/files/download/1")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.TEXT_PLAIN)
                .expectHeader().valueEquals(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"download.txt\"")
                .expectBody(byte[].class).isEqualTo(fileBytes);
    }

    @Test
    @DisplayName("GET /download/{id} should return 404 Not Found when service returns empty")
    void downloadFileById_whenNotFound_returns404() {
        // --- Arrange ---
        // 1. Mock the service to return an empty Mono, simulating "not found"
        when(s3Service.downloadFile(any(Long.class))).thenReturn(Mono.empty());

        // --- Act & Assert ---
        webTestClient.get().uri("/api/files/download/99")
                .exchange()
                .expectStatus().isNotFound();
    }
}