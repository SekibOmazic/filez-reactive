package io.filemanager.filez.files.uploader;

import software.amazon.awssdk.services.s3.model.PutObjectResponse;

// A simple record to hold the result of an upload
public record UploadResult(PutObjectResponse response, long size) {}