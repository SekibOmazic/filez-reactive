package io.filemanager.filez.service.uploader;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;

public interface S3Uploader {
    Mono<UploadResult> uploadFile(String key, Flux<ByteBuffer> fileContent, String contentType);
}