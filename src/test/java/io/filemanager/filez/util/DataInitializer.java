package io.filemanager.filez.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.waiters.WaiterResponse;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.waiters.S3AsyncWaiter;

import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final S3AsyncClient s3AsyncClient;
    private final String bucketName;

    public DataInitializer(S3AsyncClient s3AsyncClient, @Value("${s3.bucket}") String bucketName) {
        this.s3AsyncClient = s3AsyncClient;
        this.bucketName = bucketName;
    }


    @Override
    public void run(String... args) throws Exception {
        createBucketIfNotExists(bucketName)
                .subscribe(); // Trigger the reactive pipeline
    }

    private Mono<Void> createBucketIfNotExists(String bucketName) {
        return Mono.fromFuture(() -> s3AsyncClient.createBucket(b -> b.bucket(bucketName)))
                .doOnNext(createBucketResponse -> log.info("Bucket {} created successfully. Bucket arn={}", bucketName, createBucketResponse.bucketArn()))
                .then();
    }

    public CompletableFuture<Void> createBucketAsync(String bucketName) {
        CreateBucketRequest bucketRequest = CreateBucketRequest.builder()
                .bucket(bucketName)
                .build();

        CompletableFuture<CreateBucketResponse> response = s3AsyncClient.createBucket(bucketRequest);
        return response.thenCompose(resp -> {
            S3AsyncWaiter s3Waiter = s3AsyncClient.waiter();
            HeadBucketRequest bucketRequestWait = HeadBucketRequest.builder()
                    .bucket(bucketName)
                    .build();

            CompletableFuture<WaiterResponse<HeadBucketResponse>> waiterResponseFuture =
                    s3Waiter.waitUntilBucketExists(bucketRequestWait);
            return waiterResponseFuture.thenAccept(waiterResponse -> {
                waiterResponse.matched().response().ifPresent(headBucketResponse -> {
                    log.info("{} is ready", bucketName);
                });
            });
        }).whenComplete((resp, ex) -> {
            if (ex != null) {
                throw new RuntimeException("Failed to create bucket", ex);
            }
        });
    }
}
