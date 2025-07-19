package io.filemanager.filez.files.uploader;

import io.filemanager.filez.shared.config.S3Properties;
import io.filemanager.filez.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
//@TestPropertySource(properties = "s3.local=true") // <-- Set the property to enable the S3Properties bean
class StreamingMultipartS3UploaderIntegrationTest {

    @Autowired
    private S3Uploader s3Uploader;

    @Autowired
    private S3AsyncClient s3AsyncClient;

    @Autowired
    private S3Properties s3Properties;

    @Value("${s3.bucket}")
    private String bucketName; // This will be injected with the value from application-test.yaml


    // Create the bucket in the mock S3 before each test.
    // Cannot be static as it depends on autowired beans.
    @BeforeEach
    void setupBucket() {
        s3AsyncClient.createBucket(b -> b.bucket(bucketName))
                // Ignore errors if the bucket already exists from a previous test run
                .exceptionally(err -> {
                    if (err.getMessage().contains("BucketAlreadyExists")) return null;
                    throw new RuntimeException(err);
                })
                .join();
    }

    @Test
    @DisplayName("uploadFile should upload all data to S3 and return the correct size")
    void uploadFile_success() {
        // --- Arrange ---
        int fileSize = 6 * 1024 * 1024; // 6 MB
        byte[] randomBytes = new byte[fileSize];
        new Random().nextBytes(randomBytes);
        String s3Key = "test-file.bin";

        Flux<ByteBuffer> fileContent = Flux.just(ByteBuffer.wrap(randomBytes));

        // --- Act ---
        var resultMono = s3Uploader.uploadFile(s3Key, fileContent, "application/octet-stream");

        // --- Assert ---
        // 1. Verify the UploadResult
        StepVerifier.create(resultMono)
                .assertNext(uploadResult -> {
                    assertThat(uploadResult.response().eTag()).isNotNull();
                    assertThat(uploadResult.size()).isEqualTo(fileSize);
                })
                .verifyComplete();

        // 2. Verify the file exists in the mock S3
        HeadObjectRequest headRequest = HeadObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build();

        CompletableFuture<HeadObjectResponse> headFuture = s3AsyncClient.headObject(headRequest);

        StepVerifier.create(Mono.fromFuture(headFuture))
                .assertNext(headResponse -> {
                    assertThat(headResponse.contentLength()).isEqualTo(fileSize);
                })
                .verifyComplete();
    }
}