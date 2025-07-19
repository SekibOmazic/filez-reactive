package io.filemanager.filez.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.waiters.S3AsyncWaiter;


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
                .then(waitForBucketToExist(bucketName))
                .subscribe(); // Trigger the reactive pipeline
    }

    private Mono<Void> createBucketIfNotExists(String bucketName) {
        return Mono.fromFuture(() -> s3AsyncClient.createBucket(b -> b.bucket(bucketName)))
                .doOnNext(createBucketResponse -> log.info("Bucket {} created successfully. Bucket arn={}", bucketName, createBucketResponse.bucketArn()))
                .then();
    }
    private Mono<Void> waitForBucketToExist(String bucketName) {
        S3AsyncWaiter waiter = s3AsyncClient.waiter();
        HeadBucketRequest request = HeadBucketRequest.builder().bucket(bucketName).build();

        return Mono.fromFuture(() -> waiter.waitUntilBucketExists(request))
                .doOnSuccess(waiterResponse -> log.info("Bucket {} is now available.", bucketName))
                .onErrorResume(e -> {
                    log.error("Error waiting for bucket {} to exist: {}", bucketName, e.getMessage());
                    return Mono.empty();
                }).then();
    }
}
