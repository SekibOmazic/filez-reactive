package io.filemanager.filez.shared.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
public class S3Config {
    @Bean
    @ConditionalOnProperty(name="s3.local", havingValue="false", matchIfMissing = true)
    S3Properties s3Properties(@Value("${s3.endpoint}") String endpoint,
                              @Value("${s3.port}") Integer port,
                              @Value("${s3.access-key}") String accessKey,
                              @Value("${s3.secret-key}") String secretKey,
                              @Value("${s3.region:eu-central-1}") String region,
                              @Value("${s3.max-connections}") Integer maxConnections,
                              @Value("${s3.connection-timeout}") Integer connectionTimeout,
                              @Value("${s3.socket-timeout}") Integer socketTimeout)
    {
        return S3Properties.builder()
                .host(endpoint)
                .port(port)
                .accessKey(accessKey)
                .secretKey(secretKey)
                .region(region)
                .maxConnections(maxConnections)
                .connectionTimeout(connectionTimeout)
                .socketTimeout(socketTimeout)
                .build();
    }

    @Bean
    public S3AsyncClient s3AsyncClient(S3Properties s3Properties) {
        // Use the standard Netty client
        S3Configuration s3Configuration = S3Configuration.builder()
                .checksumValidationEnabled(false)
                .chunkedEncodingEnabled(true) // Recommended for streaming
                .build();

        var builder = S3AsyncClient.builder()
                .serviceConfiguration(s3Configuration)
                .region(Region.of(s3Properties.getRegion()));

        // If an endpoint is defined (for MinIO, S3Mock, etc.), use it.
        if (s3Properties.getHost() != null && !s3Properties.getHost().isEmpty()) {
            AwsBasicCredentials credentials =
                    AwsBasicCredentials.create(s3Properties.getAccessKey(), s3Properties.getSecretKey());

            builder.endpointOverride(URI.create(s3Properties.getUriAsString()))
                    .forcePathStyle(true) // Essential for most S3 mocks
                    .credentialsProvider(StaticCredentialsProvider.create(credentials));
        } else {
            // For real AWS, use default credentials and configured region
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        return builder.build();
    }
}
