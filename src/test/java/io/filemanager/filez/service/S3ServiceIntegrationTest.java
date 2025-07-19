package io.filemanager.filez.service;

import io.filemanager.filez.database.FileMetadata;
import io.filemanager.filez.database.FileMetadataRepository;
import io.filemanager.filez.service.uploader.S3Uploader;
import io.filemanager.filez.service.uploader.UploadResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.reactivestreams.Subscriber;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.async.ResponsePublisher; // <-- Note this import
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class S3ServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> String.format("r2dbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName()));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @MockitoBean
    private S3Uploader s3Uploader;
    @MockitoBean
    private S3AsyncClient s3AsyncClient;

    @Autowired
    private S3Service s3Service;

    @Autowired
    private FileMetadataRepository metadataRepository;

    @BeforeEach
    void cleanup() {
        metadataRepository.deleteAll().block();
    }

    @Test
    @DisplayName("uploadFile should save metadata, call uploader, and update file size")
    void uploadFile_success() {
        // Arrange
        FilePart mockFilePart = Mockito.mock(FilePart.class);
        when(mockFilePart.filename()).thenReturn("test-file.txt");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        when(mockFilePart.headers()).thenReturn(headers);
        byte[] fileBytes = "hello world".getBytes(StandardCharsets.UTF_8);
        DataBuffer dataBuffer = new DefaultDataBufferFactory().wrap(fileBytes);
        when(mockFilePart.content()).thenReturn(Flux.just(dataBuffer));

        long expectedSize = fileBytes.length;
        UploadResult mockUploadResult = new UploadResult(PutObjectResponse.builder().eTag("test-etag").build(), expectedSize);
        when(s3Uploader.uploadFile(any(), any(), any())).thenReturn(Mono.just(mockUploadResult));

        // Act
        Mono<FileMetadata> resultMono = s3Service.uploadFile(mockFilePart);

        // Assert
        StepVerifier.create(resultMono)
                .expectNextMatches(metadata ->
                        metadata.getFileName().equals("test-file.txt") &&
                                metadata.getFileType().equals(MediaType.TEXT_PLAIN_VALUE) &&
                                metadata.getSize() == expectedSize &&
                                metadata.getId() != null)
                .verifyComplete();
    }

    @Test
    @DisplayName("downloadFile should return file stream and metadata when ID exists")
    void downloadFile_whenIdExists_shouldReturnResult() {
        // --- Arrange ---
        FileMetadata savedMetadata = metadataRepository.save(
                new FileMetadata(null, "document.pdf", "application/pdf", 12345L)
        ).block();
        Assertions.assertNotNull(savedMetadata);
        Long fileId = savedMetadata.getId();

        // 1. Define the dummy file content as a Flux
        byte[] fileContentBytes = "dummy-pdf-content".getBytes(StandardCharsets.UTF_8);
        Flux<ByteBuffer> contentFlux = Flux.just(ByteBuffer.wrap(fileContentBytes));

        // 2. Mock the ResponsePublisher that the S3 client is expected to produce
        @SuppressWarnings("unchecked")
        ResponsePublisher<GetObjectResponse> mockResponsePublisher = Mockito.mock(ResponsePublisher.class);

        // 3. Use doAnswer to dynamically handle the "subscribe" call.
        // When the service code calls Flux.from(responsePublisher), this is triggered.
        // It bridges the subscription from the mock to our real content Flux.
        doAnswer(invocation -> {
            Subscriber<ByteBuffer> subscriber = invocation.getArgument(0);
            contentFlux.subscribe(subscriber);
            return null; // subscribe method is void
        }).when(mockResponsePublisher).subscribe(any(Subscriber.class));

        // 4. Create the CompletableFuture that the S3 client method will return
        CompletableFuture<ResponsePublisher<GetObjectResponse>> future = CompletableFuture.completedFuture(mockResponsePublisher);

        // 5. Set up the final mock for the S3 client call
        when(s3AsyncClient.getObject(
                any(GetObjectRequest.class),
                // Note the transformer now expects a ResponsePublisher
                Mockito.<AsyncResponseTransformer<GetObjectResponse, ResponsePublisher<GetObjectResponse>>>any()
        )).thenReturn(future);


        // --- Act ---
        Mono<DownloadResult> resultMono = s3Service.downloadFile(fileId);

        // --- Assert ---
        StepVerifier.create(resultMono)
                .assertNext(downloadResult -> {
                    assert downloadResult.fileName().equals("document.pdf");
                    assert downloadResult.fileType().equals("application/pdf");
                    // Now, verifying the content will work because the mock is correctly wired
                    StepVerifier.create(downloadResult.fileContent())
                            .expectNext(ByteBuffer.wrap(fileContentBytes))
                            .verifyComplete();
                })
                .verifyComplete();
    }
}