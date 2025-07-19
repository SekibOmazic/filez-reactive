package io.filemanager.filez.controller;

import io.filemanager.filez.database.FileMetadata;
import io.filemanager.filez.database.FileMetadataRepository;
import io.filemanager.filez.service.Bucket;
import io.filemanager.filez.service.S3Service;
import io.filemanager.filez.service.zipped.StreamingZipService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

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
    public Mono<ResponseEntity<FileMetadata>> uploadFile(@RequestPart("file") Mono<FilePart> filePartMono) {
        return filePartMono
                .flatMap(s3Service::uploadFile)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.badRequest().build());
    }

    /**
     * Downloads a file by its database ID, setting the correct filename in the response header.
     *
     * @param id The primary key of the file in the database.
     * @return A Mono containing the ResponseEntity with the file stream, or a 404 Not Found if the ID does not exist.
     */
    @GetMapping("/download/{id}")
    public Mono<ResponseEntity<Flux<ByteBuffer>>> downloadFileById(@PathVariable Long id) {
        return s3Service.downloadFile(id)
                .map(downloadResult -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadResult.fileName() + "\"")
                        .contentType(MediaType.parseMediaType(downloadResult.fileType()))
                        .body(downloadResult.fileContent()))
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
    }

    @PostMapping("/zip")
    public Mono<ResponseEntity<Flux<ByteBuffer>>> downloadFilesAsZip(
            @RequestBody List<Long> fileIds, // We now receive a list of database IDs
            @RequestParam(defaultValue = "archive.zip") String zipName) {

        Flux<ByteBuffer> zipStream = streamingZipService.createZipStreamFromIds(fileIds);

        return Mono.just(ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zipName + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(zipStream));
    }
}