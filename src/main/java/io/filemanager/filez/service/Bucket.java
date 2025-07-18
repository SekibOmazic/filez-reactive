package io.filemanager.filez.service;

import java.time.Instant;

public record Bucket(String name, String region, String arn, Instant creationDate){}
