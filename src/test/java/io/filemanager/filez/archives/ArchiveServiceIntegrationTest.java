package io.filemanager.filez.archives;

import io.filemanager.filez.files.File;
import io.filemanager.filez.files.FileRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.reactivestreams.Subscriber;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import software.amazon.awssdk.core.async.ResponsePublisher;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ArchiveServiceIntegrationTest {

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
    private S3AsyncClient s3AsyncClient;

    @Autowired
    private ArchiveService archiveService;

    @Autowired
    private FileRepository metadataRepository;

    @BeforeEach
    void cleanup() {
        metadataRepository.deleteAll().block();
    }

    private static class GetObjectRequestMatcher implements ArgumentMatcher<GetObjectRequest> {
        private final String expectedKey;

        private GetObjectRequestMatcher(String key) {
            this.expectedKey = key;
        }

        @Override
        public boolean matches(GetObjectRequest argument) {
            return argument != null && argument.key().equals(expectedKey);
        }
    }

    private void mockS3GetObject(String key, String content) {
        Flux<ByteBuffer> contentFlux = Flux.just(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));

        @SuppressWarnings("unchecked")
        ResponsePublisher<GetObjectResponse> mockResponsePublisher = Mockito.mock(ResponsePublisher.class);

        doAnswer(invocation -> {
            Subscriber<ByteBuffer> subscriber = invocation.getArgument(0);
            contentFlux.subscribe(subscriber);
            return null;
        }).when(mockResponsePublisher).subscribe(any(Subscriber.class));

        CompletableFuture<ResponsePublisher<GetObjectResponse>> future = CompletableFuture.completedFuture(mockResponsePublisher);

        when(s3AsyncClient.getObject(
                Mockito.argThat(new GetObjectRequestMatcher(key)),
                Mockito.<AsyncResponseTransformer<GetObjectResponse, ResponsePublisher<GetObjectResponse>>>any()
        )).thenReturn(future);
    }

    @Test
    @DisplayName("createZipStreamFromIds should produce a valid zip stream with correct files and content")
    void createZipStream_success() {
        // --- Arrange ---
        File file1 = metadataRepository.save(new File(null, "first-file.txt", "text/plain", 10L, null, null)).block();
        File file2 = metadataRepository.save(new File(null, "another/document.csv", "text/csv", 20L, null, null)).block();
        Assertions.assertNotNull(file1);
        Long id1 = file1.getId();
        String key1 = id1 + "-" + file1.getFileName();
        String content1 = "This is the content of the first file.";

        Assertions.assertNotNull(file2);
        Long id2 = file2.getId();
        String key2 = id2 + "-" + file2.getFileName();
        String content2 = "col1,col2\nval1,val2";

        mockS3GetObject(key1, content1);
        mockS3GetObject(key2, content2);

        // --- Act ---
        Flux<ByteBuffer> zipStream = archiveService.createZipStreamFromIds(List.of(id1, id2));

        // --- Assert ---
        Mono<byte[]> aggregatedBytesMono = zipStream
                .collectList()
                .map(this::aggregateBuffers);

        StepVerifier.create(aggregatedBytesMono)
                .assertNext(zipBytes -> {
                    Map<String, String> zippedContents = new HashMap<>();
                    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            zis.transferTo(baos);
                            zippedContents.put(entry.getName(), baos.toString(StandardCharsets.UTF_8));
                            zis.closeEntry();
                        }
                    } catch (IOException e) {
                        fail("Failed to read zip stream", e);
                    }
                    assertThat(zippedContents).hasSize(2);
                    assertThat(zippedContents).containsEntry(key1, content1);
                    assertThat(zippedContents).containsEntry(key2, content2);
                })
                .verifyComplete();
    }

    private byte[] aggregateBuffers(List<ByteBuffer> buffers) {
        int totalSize = buffers.stream().mapToInt(ByteBuffer::remaining).sum();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(totalSize);
        buffers.forEach(buffer -> {
            byte[] arr = new byte[buffer.remaining()];
            buffer.get(arr);
            baos.write(arr, 0, arr.length);
        });
        return baos.toByteArray();
    }
}