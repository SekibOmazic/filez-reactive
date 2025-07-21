package io.filemanager.filez.files.uploader;

import software.amazon.awssdk.services.s3.model.PutObjectResponse;

public record UploadResult(PutObjectResponse response, long size) {}