package io.filemanager.filez.files;

import io.filemanager.filez.files.uploader.S3Uploader;
import io.filemanager.filez.shared.dto.Bucket;
import io.filemanager.filez.shared.dto.DownloadResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;


/**
 * Service for handling business logic related to AWS S3 operations using S3AsyncClient.
 */
@Service
public class FileService {

    private final S3AsyncClient s3AsyncClient;
    private final S3Uploader s3Uploader;
    private final String bucketName;
    private final FileRepository fileRepository;


    public FileService(S3AsyncClient s3AsyncClient, S3Uploader s3Uploader, @Value("${s3.bucket}") String bucketName, FileRepository fileRepository) {
        this.s3AsyncClient = s3AsyncClient;
        this.s3Uploader = s3Uploader;
        this.bucketName = bucketName;
        this.fileRepository = fileRepository;
    }

    public Mono<List<Bucket>> getBuckets() {
        return Mono.fromFuture(s3AsyncClient.listBuckets())
                .map(ListBucketsResponse::buckets)
                .map(buckets ->
                        buckets.stream().map(bucket -> new Bucket(
                                bucket.name(),
                                bucket.bucketRegion(),
                                bucket.bucketArn(),
                                bucket.creationDate()
                        )).toList()
                );
    }

    /**
     * Uploads a file to S3 using a non-blocking, streaming approach.
     *
     * @param filePart A FilePart from a WebFlux request, representing the file to upload.
     * @return A Mono that completes with the PutObjectResponse when the upload is finished.
     */
    public Mono<File> uploadFile(FilePart filePart) {
        String fileName = filePart.filename();
        // Get the content type, defaulting to a generic stream if not present.
        String contentType = Objects.toString(filePart.headers().getContentType(), MediaType.APPLICATION_OCTET_STREAM_VALUE);

        // Convert the file's content from a Flux<DataBuffer> to a Flux<ByteBuffer>.
        // This is the required type for the AWS SDK v2's async request bodies.
        // Using flatMapSequential to ensure that the DataBuffers are processed in order.
        Flux<ByteBuffer> fileContent = filePart.content()
                .flatMapSequential(dataBuffer -> Flux.fromIterable(dataBuffer::readableByteBuffers));

        File initialMetadata = new File();
        initialMetadata.setFileName(fileName);
        initialMetadata.setFileType(contentType);
        initialMetadata.setSize(0L);

        return fileRepository.save(initialMetadata)
                .flatMap(savedMetadata -> {
                    String s3Key = savedMetadata.getId() + "-" + savedMetadata.getFileName();

                    // Perform the upload. After it's done, combine its result (uploadResult)
                    // with the data we already have (savedMetadata) into a Tuple.
                    // We could also use a custom class instead of Tuples, but for simplicity,
                    // we use Tuples here.
                    return s3Uploader.uploadFile(s3Key, fileContent, contentType)
                            .map(uploadResult -> Tuples.of(savedMetadata, uploadResult));
                })
                .flatMap(tuple -> {
                    // Unpack the tuple containing both pieces of information
                    File metadataToUpdate = tuple.getT1();
                    var uploadResult = tuple.getT2();

                    // Update the metadata object with the final size
                    metadataToUpdate.setSize(uploadResult.size());

                    // Perform the final save (this is an update operation)
                    return fileRepository.save(metadataToUpdate);
                });
    }


    /**
     * Downloads a file by its ID. It fetches metadata from the database and then streams
     * the corresponding file from S3.
     *
     * @param id The primary key of the file in the database.
     * @return A Mono containing a DownloadResult with the file's stream and metadata,
     * or an empty Mono if the ID is not found.
     */
    public Mono<DownloadResult> downloadFile(Long id) {
        return fileRepository.findById(id)
                .flatMap(metadata -> {
                    String s3Key = metadata.getId() + "-" + metadata.getFileName();
                    GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                            .bucket(bucketName)
                            .key(s3Key)
                            .build();

                    // Get the file stream from S3.
                    Flux<ByteBuffer> fileStream = Mono.fromFuture(s3AsyncClient.getObject(getObjectRequest, AsyncResponseTransformer.toPublisher()))
                            .flatMapMany(Flux::from);

                    return Mono.just(new DownloadResult(metadata.getFileName(), metadata.getFileType(), fileStream));
                });
        // If findById returns empty, the whole chain will result in an empty Mono.
    }
}