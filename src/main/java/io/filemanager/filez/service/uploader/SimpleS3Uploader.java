package io.filemanager.filez.service.uploader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.nio.ByteBuffer;
import java.util.List;

@Component
@Profile("simple")
public class SimpleS3Uploader implements S3Uploader {

    private final S3AsyncClient s3AsyncClient;
    private final String bucketName;

    public SimpleS3Uploader(S3AsyncClient s3AsyncClient, @Value("${s3.bucket}") String bucketName) {
        this.s3AsyncClient = s3AsyncClient;
        this.bucketName = bucketName;
    }

    @Override
    public Mono<PutObjectResponse> uploadFile(String key, Flux<ByteBuffer> fileContent, String contentType) {
        // Step 1: Collect all incoming ByteBuffer chunks into a list. The `fileContent`
        // Flux is created in the controller via `filePart.content().map(DataBuffer::asByteBuffer)`.
        Mono<List<ByteBuffer>> listMono = fileContent.collectList();

        return listMono.flatMap(byteBuffers -> {
            // Step 2: Calculate the total size of the final buffer by summing the sizes of all chunks.
            int totalSize = byteBuffers.stream().mapToInt(ByteBuffer::remaining).sum();

            // Step 3: Allocate a single, new ByteBuffer in memory to hold all the data.
            ByteBuffer combinedBuffer = ByteBuffer.allocate(totalSize);

            // Step 4: Copy each chunk from the list into our single combined buffer.
            byteBuffers.forEach(combinedBuffer::put);

            // Step 5: IMPORTANT! Flip the buffer. This resets its position to 0 and sets its limit
            // to the current position, preparing it to be read by the SDK.
            combinedBuffer.flip();

            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(contentType)
                    .build();

            // Step 6: Use AsyncRequestBody.fromByteBuffer. The SDK will automatically
            // determine the Content-Length from the buffer's "remaining()" size.
            AsyncRequestBody requestBody = AsyncRequestBody.fromByteBuffer(combinedBuffer);

            // This now works because the request will have a Content-Length.
            return Mono.fromFuture(s3AsyncClient.putObject(request, requestBody));
        });
    }
}
