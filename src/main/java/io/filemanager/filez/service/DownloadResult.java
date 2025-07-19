package io.filemanager.filez.service;

import reactor.core.publisher.Flux;

import java.nio.ByteBuffer;

public record DownloadResult(String fileName, String fileType, Flux<ByteBuffer> fileContent) { }
