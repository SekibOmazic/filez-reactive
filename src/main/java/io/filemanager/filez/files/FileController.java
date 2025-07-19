package io.filemanager.filez.files;

import io.filemanager.filez.shared.dto.Bucket;
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

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @GetMapping("/buckets")
    public Mono<ResponseEntity<List<Bucket>>> getBucketName() {
        return fileService.getBuckets()
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
    public Mono<ResponseEntity<File>> uploadFile(@RequestPart("file") Mono<FilePart> filePartMono) {
        return filePartMono
                .flatMap(fileService::uploadFile)
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
        return fileService.downloadFile(id)
                .map(downloadResult -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadResult.fileName() + "\"")
                        .contentType(MediaType.parseMediaType(downloadResult.fileType()))
                        .body(downloadResult.fileContent()))
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
    }

}