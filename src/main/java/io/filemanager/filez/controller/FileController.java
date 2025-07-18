package io.filemanager.filez.controller;

import io.filemanager.filez.service.Bucket;
import io.filemanager.filez.service.S3Service;
import io.filemanager.filez.service.zipped.StreamingZipService;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * REST Controller for handling file uploads and downloads using Spring WebFlux and S3AsyncClient.
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final S3Service s3Service;
    private final StreamingZipService streamingZipService;

    public FileController(S3Service s3Service, StreamingZipService streamingZipService) {
        this.s3Service = s3Service;
        this.streamingZipService = streamingZipService;
    }

    @GetMapping("/buckets")
    public Mono<ResponseEntity<List<Bucket>>> getBucketName() {
        return s3Service.getBuckets()
                .map(ResponseEntity::ok);
//                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
//                        .body("Error retrieving buckets: " + e.getMessage())));

    }
    /**
     * Endpoint for uploading a file in a non-blocking manner.
     *
     * @param filePartMono A Mono of the FilePart to upload.
     * @return A Mono<ResponseEntity> that completes when the upload is done.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<String>> uploadFile(@RequestPart("file") Mono<FilePart> filePartMono) {
        return filePartMono
                .flatMap(s3Service::uploadFile)
                .map(response -> ResponseEntity.ok("File uploaded successfully. ETag: " + response.eTag()))
                .defaultIfEmpty(ResponseEntity.badRequest().body("Please select a file to upload."));
    }

    /**
     * Endpoint for downloading a file in a non-blocking, streaming manner.
     *
     * @param filename The name of the file to download.
     * @return A ResponseEntity containing the Flux of data buffers.
     */
    @GetMapping("/download/{filename:.+}")
    public ResponseEntity<Flux<DataBuffer>> downloadFile(@PathVariable String filename) {
        Flux<DataBuffer> fileStream = s3Service.downloadFile(filename);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(fileStream);
    }

    @PostMapping("/zip")
    public Mono<ResponseEntity<Flux<ByteBuffer>>> downloadFilesAsZip(
            @RequestBody List<String> fileKeys,
            @RequestParam(defaultValue = "archive.zip") String zipName) {

        Flux<ByteBuffer> zipStream = streamingZipService.createZipStream(fileKeys);

        return Mono.just(ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zipName + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(zipStream));
    }
}