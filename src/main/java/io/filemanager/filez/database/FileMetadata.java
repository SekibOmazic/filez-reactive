package io.filemanager.filez.database;

import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Data
@NoArgsConstructor
@Table("file_metadata")
public class FileMetadata {

    public FileMetadata(Long id, String fileName, String contentType, long size) {
        this.id = id;
        this.fileName = fileName;
        this.fileType = contentType;
        this.size = size;
    }

    public FileMetadata(Long id, String fileName, String contentType, long size, Instant createdAt, Instant updatedAt) {
        this(id, fileName, contentType, size);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    @Id
    private Long id;

    @Column("file_name")
    private String fileName;

    @Column("file_type")
    private String fileType;

    private long size;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;



}