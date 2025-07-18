package io.filemanager.filez.service;

import io.filemanager.filez.service.uploader.S3Uploader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;


import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;


/**
 * Service for handling business logic related to AWS S3 operations using S3AsyncClient.
 */
@Service
public class S3Service {

    private final S3AsyncClient s3AsyncClient;
    private final S3Uploader s3Uploader;
    private final String bucketName;

    public S3Service(S3AsyncClient s3AsyncClient, S3Uploader s3Uploader, @Value("${s3.bucket}") String bucketName) {
        this.s3AsyncClient = s3AsyncClient;
        this.s3Uploader = s3Uploader;
        this.bucketName = bucketName;
    }

    public Mono<List<Bucket>> getBuckets() {
        return Mono.fromFuture(s3AsyncClient.listBuckets()).map(ListBucketsResponse::buckets).map(buckets -> buckets.stream().map(bucket -> new Bucket(bucket.name(), bucket.bucketRegion(), bucket.bucketArn(), bucket.creationDate())).toList());
    }

    /**
     * Uploads a file to S3 using a non-blocking, streaming approach.
     *
     * @param filePart A FilePart from a WebFlux request, representing the file to upload.
     * @return A Mono that completes with the PutObjectResponse when the upload is finished.
     */
    public Mono<PutObjectResponse> uploadFile(FilePart filePart) {
        String key = filePart.filename();
        // Get the content type, defaulting to a generic stream if not present.
        String contentType = Objects.toString(filePart.headers().getContentType(), MediaType.APPLICATION_OCTET_STREAM_VALUE);

        // Convert the file's content from a Flux<DataBuffer> to a Flux<ByteBuffer>.
        // This is the required type for the AWS SDK v2's async request bodies.
        Flux<ByteBuffer> fileContent = filePart.content().map(DataBuffer::asByteBuffer);

        return s3Uploader.uploadFile(key, fileContent, contentType);
    }



    public Mono<PutObjectResponse> uploadFile_OLD(FilePart filePart) {
        String fileKey = filePart.filename();

        // Build the request object with metadata.
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(fileKey)
                .contentType(Objects.requireNonNull(filePart.headers().getContentType()).toString())
                //.contentLength(filePart.headers().getContentLength())
                .build();

        // The AsyncRequestBody.fromPublisher streams the file content directly.
        // The Flux<ByteBuffer> from filePart.content() is published to S3 without
        // buffering the entire file in memory.
        AsyncRequestBody requestBody = AsyncRequestBody.fromPublisher(
            filePart.content().map(DataBuffer::asByteBuffer)
        );

        // The putObject method returns a CompletableFuture, which we convert to a Mono.
        return Mono.fromFuture(s3AsyncClient.putObject(putObjectRequest, requestBody));
    }

    /**
     * Downloads a file from S3 as a non-blocking stream of data.
     *
     * @param fileKey The key of the file to download.
     * @return A Flux of DataBuffers representing the file's content.
     */
    public Flux<DataBuffer> downloadFile(String fileKey) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(fileKey)
                .build();

        // The AsyncResponseTransformer.toPublisher() is key here. It transforms the
        // incoming S3 data into a Publisher (which we can use as a Flux) of ByteBuffers.
        // This ensures the data is streamed from S3 and sent to the client as it arrives.
        return Mono.fromFuture(s3AsyncClient.getObject(
                getObjectRequest,
                AsyncResponseTransformer.toPublisher()
        )).flatMapMany(response -> Flux.from(response).map(
                DefaultDataBufferFactory.sharedInstance::wrap
        ));
    }
}