package io.filemanager.filez.database;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FileMetadataRepository extends ReactiveCrudRepository<FileMetadata, Long> {}