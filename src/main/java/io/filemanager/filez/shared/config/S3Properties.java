package io.filemanager.filez.shared.config;

import lombok.Builder;
import lombok.Data;


@Data
@Builder
public class S3Properties {
    @Builder.Default
    private boolean secure = false;
    private String host;
    private Integer port;
    private String accessKey;
    private String secretKey;
    private String region;
    private Integer maxConnections;
    private Integer connectionTimeout;
    private Integer socketTimeout;

    public String getUriAsString() {
        String schema = secure ? "https" : "http";
        return schema + "://" + host + ":" + port;
    }
}
