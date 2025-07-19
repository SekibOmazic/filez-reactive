package io.filemanager.filez.shared.dto;

import java.time.Instant;

public record Bucket(String name, String region, String arn, Instant creationDate){}
