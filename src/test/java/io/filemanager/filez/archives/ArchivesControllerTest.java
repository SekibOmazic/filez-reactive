package io.filemanager.filez.archives;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebFluxTest(ArchivesController.class)
class ArchivesControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ArchiveService archiveService;


    @Test
    @DisplayName("POST /download-zip should call service and return a zip stream with correct headers")
    void downloadFilesAsZip_success() {
        // --- Arrange ---
        // 1. Define the mock data that the service will return.
        String fakeZipContent = "this-is-fake-zip-data";
        byte[] fakeBytes = fakeZipContent.getBytes(StandardCharsets.UTF_8);
        Flux<ByteBuffer> mockStream = Flux.just(ByteBuffer.wrap(fakeBytes));

        // 2. Define the request payload that our test will send.
        List<Long> requestFileIds = List.of(1L, 2L, 3L);
        String customZipName = "my-archive.zip";

        // 3. Mock the service call. When createZipStreamFromIds is called with any list,
        //    return our predefined mock stream.
        when(archiveService.createZipStreamFromIds(any(List.class))).thenReturn(mockStream);

        // --- Act & Assert ---
        // Use the WebTestClient to perform the POST request.
        webTestClient.post()
                // Build the URI with the path and the request parameter
                .uri(uriBuilder -> uriBuilder
                        .path("/api/archives/download-zip")
                        .queryParam("zipName", customZipName)
                        .build())
                // Set the content type of our request
                .contentType(MediaType.APPLICATION_JSON)
                // Set the request body
                .bodyValue(requestFileIds)
                // Execute the request
                .exchange()
                // Verify the response
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .expectHeader().valueEquals(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + customZipName + "\"")
                .expectBody(byte[].class).isEqualTo(fakeBytes);
    }
}