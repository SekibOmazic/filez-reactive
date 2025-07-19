package io.filemanager.filez.service.zipped;

import io.filemanager.filez.database.FileMetadataRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

@Service
public class StreamingZipService {

    private final S3AsyncClient s3AsyncClient;
    private final String bucketName;
    private final FileMetadataRepository metadataRepository;


    public StreamingZipService(S3AsyncClient s3AsyncClient, @Value("${s3.bucket}") String bucketName, FileMetadataRepository metadataRepository) {
        this.s3AsyncClient = s3AsyncClient;
        this.bucketName = bucketName;
        this.metadataRepository = metadataRepository;
    }

    public Flux<ByteBuffer> createZipStreamFromIds(List<Long> ids) {
        // Find all metadata records, then map them to their S3 keys
        Flux<String> s3Keys = metadataRepository.findAllById(ids)
                .map(metadata -> metadata.getId() + "-" + metadata.getFileName());

        // Collect the keys into a list and pass to our existing zip logic
        return s3Keys.collectList().flatMapMany(this::createZipStream);
    }

    private Flux<ByteBuffer> createZipStream(List<String> s3Keys) {
        // This list will be populated as a side-effect when each file stream completes.
        final List<ZipEntryInfo> zipEntries = new ArrayList<>();

        // Create a stream of file entries. Each entry is a Flux<ByteBuffer>.
        Flux<Flux<ByteBuffer>> fileStreams = Flux.fromIterable(s3Keys)
                .map(key -> createZipEntryStream(key, zipEntries));

        // Concatenate all the individual file entry streams into one single stream.
        Flux<ByteBuffer> combinedStream = Flux.concat(fileStreams);

        // After all file data has been streamed, create and append the central directory.
        // The Mono returned here will only be subscribed to after the combinedStream completes.
        return combinedStream.concatWith(createCentralDirectoryStream(zipEntries));
    }


    /**
     * Creates a reactive stream for a single ZIP entry, consisting of:
     * [Local File Header] -> [Compressed File Data] -> [Data Descriptor]
     */
    private Flux<ByteBuffer> createZipEntryStream(String s3Key, List<ZipEntryInfo> zipEntries) {
        final CRC32 crc = new CRC32();
        final AtomicLong uncompressedSize = new AtomicLong(0);
        final AtomicLong compressedSize = new AtomicLong(0);
        final Deflater deflater = new Deflater(Deflater.DEFLATED, true);

        GetObjectRequest request = GetObjectRequest.builder().bucket(bucketName).key(s3Key).build();
        Flux<ByteBuffer> s3FileStream = Mono.fromFuture(s3AsyncClient.getObject(request, AsyncResponseTransformer.toPublisher()))
                .flatMapMany(Flux::from);

        Flux<ByteBuffer> compressedDataStream = s3FileStream
                .concatMap(buffer -> {
                    crc.update(buffer.duplicate());
                    uncompressedSize.addAndGet(buffer.remaining());
                    deflater.setInput(buffer);

                    List<ByteBuffer> resultChunks = new ArrayList<>();
                    while (!deflater.needsInput()) {
                        ByteBuffer compressedChunk = ByteBuffer.allocate(8192);
                        int bytesCompressed = deflater.deflate(compressedChunk, Deflater.SYNC_FLUSH);
                        if (bytesCompressed > 0) {
                            compressedChunk.flip();
                            resultChunks.add(copyByteBuffer(compressedChunk));
                            compressedSize.addAndGet(bytesCompressed);
                        } else {
                            break;
                        }
                    }
                    return Flux.fromIterable(resultChunks);
                })
                .concatWith(Mono.fromCallable(() -> {
                    ByteBuffer finalChunk = ByteBuffer.allocate(8192);
                    deflater.finish();
                    int remainingBytes = deflater.deflate(finalChunk);
                    if (remainingBytes > 0) {
                        finalChunk.flip();
                        compressedSize.addAndGet(remainingBytes);
                        return copyByteBuffer(finalChunk);
                    }
                    return null;
                }).filter(b -> b != null))
                .doOnComplete(deflater::end); // Clean up native resources

        Mono<ByteBuffer> dataDescriptorStream = Mono.fromCallable(() -> {
            // This runs after the file content is fully streamed and compressed.
            // We now have the final metadata for this file.
            ZipEntryInfo entryInfo = new ZipEntryInfo(s3Key, crc.getValue(), compressedSize.get(), uncompressedSize.get());
            zipEntries.add(entryInfo); // Add the completed entry to our list for later processing.
            return createDataDescriptor(entryInfo);
        });

        // The local header is simple; it no longer tries to calculate any offsets.
        Mono<ByteBuffer> localHeaderStream = Mono.fromCallable(() -> createLocalFileHeader(s3Key));

        return Flux.concat(localHeaderStream, compressedDataStream, dataDescriptorStream);
    }

    /**
     * Creates the central directory and end-of-directory records.
     * This method is called ONLY after all file data has been streamed.
     * At this point, the `zipEntries` list is fully populated with metadata.
     */
    private Mono<ByteBuffer> createCentralDirectoryStream(List<ZipEntryInfo> zipEntries) {
        return Mono.fromCallable(() -> {
            // 1. Calculate the final offsets deterministically.
            long currentOffset = 0;
            for (ZipEntryInfo entry : zipEntries) {
                entry.setLocalHeaderOffset(currentOffset);
                // The size of one full entry = header + compressed data + descriptor
                long entrySize = 30L + entry.getFileNameBytes().length + entry.getCompressedSize() + 16;
                currentOffset += entrySize;
            }
            long centralDirectoryStartOffset = currentOffset;

            // 2. Now, build the central directory using the correct offsets.
            ByteBuffer centralDirectoryBuffer = ByteBuffer.allocate(1024 * 10 * zipEntries.size());
            centralDirectoryBuffer.order(ByteOrder.LITTLE_ENDIAN);

            for (ZipEntryInfo entry : zipEntries) {
                centralDirectoryBuffer.putInt(0x02014b50); // Signature
                centralDirectoryBuffer.putShort((short) 20);
                centralDirectoryBuffer.putShort((short) 20);
                centralDirectoryBuffer.putShort((short) (1 << 3));
                centralDirectoryBuffer.putShort((short) 8);
                putDosTime(centralDirectoryBuffer, Instant.now());
                centralDirectoryBuffer.putInt((int) entry.getCrc());
                centralDirectoryBuffer.putInt((int) entry.getCompressedSize());
                centralDirectoryBuffer.putInt((int) entry.getUncompressedSize());
                centralDirectoryBuffer.putShort((short) entry.getFileNameBytes().length);
                centralDirectoryBuffer.putShort((short) 0);
                centralDirectoryBuffer.putShort((short) 0);
                centralDirectoryBuffer.putShort((short) 0);
                centralDirectoryBuffer.putShort((short) 0);
                centralDirectoryBuffer.putInt(0);
                centralDirectoryBuffer.putInt((int) entry.getLocalHeaderOffset()); // Use the calculated offset
                centralDirectoryBuffer.put(entry.getFileNameBytes());
            }

            long centralDirectorySize = centralDirectoryBuffer.position();

            // 3. Build the End of Central Directory Record.
            centralDirectoryBuffer.putInt(0x06054b50); // Signature
            centralDirectoryBuffer.putShort((short) 0);
            centralDirectoryBuffer.putShort((short) 0);
            centralDirectoryBuffer.putShort((short) zipEntries.size());
            centralDirectoryBuffer.putShort((short) zipEntries.size());
            centralDirectoryBuffer.putInt((int) centralDirectorySize);
            centralDirectoryBuffer.putInt((int) centralDirectoryStartOffset);
            centralDirectoryBuffer.putShort((short) 0);

            // 4. Finalize the buffer for sending.
            centralDirectoryBuffer.flip();
            ByteBuffer resultBuffer = ByteBuffer.allocate(centralDirectoryBuffer.remaining());
            resultBuffer.put(centralDirectoryBuffer);
            resultBuffer.flip();
            return resultBuffer;
        });
    }

    private ByteBuffer createLocalFileHeader(String fileName) {
        byte[] fileNameBytes = fileName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(30 + fileNameBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) (1 << 3));
        buffer.putShort((short) 8);
        putDosTime(buffer, Instant.now());
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) fileNameBytes.length);
        buffer.putShort((short) 0);
        buffer.put(fileNameBytes);
        buffer.flip();
        return buffer;
    }

    private ByteBuffer createDataDescriptor(ZipEntryInfo info) {
        ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x08074b50);
        buffer.putInt((int) info.getCrc());
        buffer.putInt((int) info.getCompressedSize());
        buffer.putInt((int) info.getUncompressedSize());
        buffer.flip();
        return buffer;
    }

    private void putDosTime(ByteBuffer buffer, Instant time) {
        ZonedDateTime zdt = ZonedDateTime.ofInstant(time, ZoneId.systemDefault());
        int dosTime = (zdt.getYear() - 1980) << 25 |
                (zdt.getMonthValue()) << 21 |
                (zdt.getDayOfMonth()) << 16 |
                (zdt.getHour()) << 11 |
                (zdt.getMinute()) << 5 |
                (zdt.getSecond()) >> 1;
        buffer.putInt(dosTime);
    }

    private ByteBuffer copyByteBuffer(ByteBuffer original) {
        ByteBuffer copy = ByteBuffer.allocate(original.remaining());
        copy.put(original);
        copy.flip();
        return copy;
    }
}