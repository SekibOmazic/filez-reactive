package io.filemanager.filez;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import io.filemanager.filez.shared.config.S3Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Slf4j
@ActiveProfiles("test")
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {
    private static final String S3_MOCK = "adobe/s3mock:latest";
    private static final String POSTGRES_IMAGE = "postgres:13.1-alpine";

    ///////////////
    // CONTAINERS
    /// ////////////
    @Bean
    public S3MockContainer s3mock() {
        log.warn("Starting S3 Mock container with image: {}", S3_MOCK);

        return new S3MockContainer(DockerImageName.parse(S3_MOCK))
                .withValidKmsKeys("arn:aws:kms:us-east-1:1234567890:key/valid-test-key-ref");
    }

/*
    we use sprint.sql.init.mode=true, see application-test.yaml

    @Bean
    public ConnectionFactoryInitializer initializer(ConnectionFactory connectionFactory) {
        ConnectionFactoryInitializer initializer = new ConnectionFactoryInitializer();

        initializer.setConnectionFactory(connectionFactory);
        initializer.setDatabasePopulator(
                new ResourceDatabasePopulator(new ClassPathResource("schema.sql"))
        );
        return initializer;
    }
*/

    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        log.warn("Starting PostgreSQL container with image: {}", POSTGRES_IMAGE);

        return new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                .withDatabaseName("testdb")
                .withUsername("testuser")
                .withPassword("testpass")
                .withExposedPorts(5432);
    }

    ///////////////
    // PROPERTIES
    ///////////////
    @Bean
    @ConditionalOnProperty(name="s3.local", havingValue="true")
    public S3Properties s3Properties(S3MockContainer s3mock,
                                     @Value("${s3.endpoint:}") String endpoint,
                                     @Value("${s3.port:}") Integer port,
                                     @Value("${s3.access-key:mock-access-key}") String accessKey,
                                     @Value("${s3.secret-key:mock-secret-key}") String secretKey,
                                     @Value("${s3.region:eu-central-1}") String region,
                                     @Value("${s3.max-connections:100}") Integer maxConnections,
                                     @Value("${s3.connection-timeout:60}") Integer connectionTimeout,
                                     @Value("${s3.socket-timeout:60}") Integer socketTimeout

    ) {
        String s3host = endpoint.isEmpty() ? s3mock.getHost() : endpoint;
        Integer s3port = port == null ? s3mock.getMappedPort(9090) : port;

        return S3Properties.builder()
                .host(s3host)
                .port(s3port)
                .accessKey(accessKey)
                .secretKey(secretKey)
                .region(region)
                .maxConnections(maxConnections)
                .connectionTimeout(connectionTimeout)
                .socketTimeout(socketTimeout)
                .build();
    }

}
