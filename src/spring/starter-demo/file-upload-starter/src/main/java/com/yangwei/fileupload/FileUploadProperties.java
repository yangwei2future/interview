package com.yangwei.fileupload;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对接方在 application.yml 里配置：
 *
 * file-upload:
 *   server-url: https://your-file-system.com
 *   env: prod
 *   app-key: your-app-key
 */
@ConfigurationProperties(prefix = "file-upload")
public class FileUploadProperties {

    private String serverUrl = "http://localhost:8080";
    private String env = "dev";
    private String appKey = "";

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }

    public String getAppKey() { return appKey; }
    public void setAppKey(String appKey) { this.appKey = appKey; }
}
