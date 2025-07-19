package io.filemanager.filez.archives;

import lombok.*;

import java.nio.charset.StandardCharsets;

@Getter
@Setter
@RequiredArgsConstructor
public class ZipEntryInfo {
    private final String fileName;
    private final long crc;
    private final long compressedSize;
    private final long uncompressedSize;
    private long localHeaderOffset; // Will be set as we stream

    public byte[] getFileNameBytes() { return fileName.getBytes(StandardCharsets.UTF_8); }
}