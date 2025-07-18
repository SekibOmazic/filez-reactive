package io.filemanager.filez.service.uploader;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.nio.ByteBuffer;

public interface S3Uploader {
    Mono<PutObjectResponse> uploadFile(String key, Flux<ByteBuffer> fileContent, String contentType);
}