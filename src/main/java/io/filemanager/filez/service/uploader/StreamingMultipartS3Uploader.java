package io.filemanager.filez.service.uploader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Profile("test")
public class StreamingMultipartS3Uploader implements S3Uploader {

    private static final int PART_SIZE_IN_BYTES = 5 * 1024 * 1024;

    private final S3AsyncClient s3AsyncClient;
    private final String bucketName;

    public StreamingMultipartS3Uploader(S3AsyncClient s3AsyncClient, @Value("${s3.bucket}") String bucketName) {
        this.s3AsyncClient = s3AsyncClient;
        this.bucketName = bucketName;
    }

    @Override
    public Mono<PutObjectResponse> uploadFile(String key, Flux<ByteBuffer> fileContent, String contentType) {
        CreateMultipartUploadRequest createRequest = CreateMultipartUploadRequest.builder()
                .bucket(bucketName).key(key).contentType(contentType).build();

        return Mono.fromFuture(s3AsyncClient.createMultipartUpload(createRequest))
                .flatMap(createResponse -> {
                    String uploadId = createResponse.uploadId();
                    AtomicInteger partNumber = new AtomicInteger(1);

                    Flux<CompletedPart> completedPartsFlux = fileContent
                            .bufferTimeout(PART_SIZE_IN_BYTES, Duration.ofSeconds(5))
                            .concatMap(byteBufferList -> uploadPart(uploadId, key, partNumber.getAndIncrement(), byteBufferList));

                    return completedPartsFlux.collectList()
                            .flatMap(completedParts -> completeUpload(uploadId, key, completedParts))
                            .doOnError(ex -> abortUpload(uploadId, key));
                });
    }

    private Mono<CompletedPart> uploadPart(String uploadId, String key, int partNumber, List<ByteBuffer> byteBufferList) {
        int totalSize = byteBufferList.stream().mapToInt(ByteBuffer::remaining).sum();
        ByteBuffer combinedBuffer = ByteBuffer.allocate(totalSize);
        byteBufferList.forEach(combinedBuffer::put);
        combinedBuffer.flip();

        UploadPartRequest partRequest = UploadPartRequest.builder()
                .bucket(bucketName).key(key).uploadId(uploadId).partNumber(partNumber).build();

        return Mono.fromFuture(s3AsyncClient.uploadPart(partRequest, AsyncRequestBody.fromByteBuffer(combinedBuffer)))
                .map(response -> CompletedPart.builder().partNumber(partNumber).eTag(response.eTag()).build());
    }

    private Mono<PutObjectResponse> completeUpload(String uploadId, String key, List<CompletedPart> parts) {
        parts.sort(Comparator.comparingInt(CompletedPart::partNumber));
        CompletedMultipartUpload completedMultipartUpload = CompletedMultipartUpload.builder().parts(parts).build();
        CompleteMultipartUploadRequest completeRequest = CompleteMultipartUploadRequest.builder()
                .bucket(bucketName).key(key).uploadId(uploadId)
                .multipartUpload(completedMultipartUpload).build();

        return Mono.fromFuture(s3AsyncClient.completeMultipartUpload(completeRequest))
                .map(completeResponse -> {
                    return PutObjectResponse.builder()
                            .eTag(completeResponse.eTag())
                            .versionId(completeResponse.versionId())
                            .build();
                });
    }

    private void abortUpload(String uploadId, String key) {
        AbortMultipartUploadRequest abortRequest = AbortMultipartUploadRequest.builder()
                .bucket(bucketName).key(key).uploadId(uploadId).build();
        s3AsyncClient.abortMultipartUpload(abortRequest);
    }
}