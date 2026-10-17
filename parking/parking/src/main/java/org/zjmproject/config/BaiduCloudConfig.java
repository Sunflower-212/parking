package org.zjmproject.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "baidu.cloud")
public class BaiduCloudConfig {

    private String app_api;
    private String api_key;
    private String secret_key;
}
