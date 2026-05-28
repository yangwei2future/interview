package com.interview.deepseek;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DeepSeek API 配置属性，在 application.yml 中配置：
 *
 * deepseek:
 *   api-key: sk-xxxx
 *   base-url: https://api.deepseek.com
 *   model: deepseek-chat
 *   max-tokens: 2048
 *   temperature: 0.7
 */
@ConfigurationProperties(prefix = "deepseek.api")
public class DeepSeekProperties {

    /** API Key（必填） */
    private String apiKey;

    /** API 地址，默认官方地址 */
    private String baseUrl = "https://api.deepseek.com";

    /** 模型名称 */
    private String model = "deepseek-chat";

    /** 最大 Token 数 */
    private int maxTokens = 2048;

    /** 温度（0-2），越高越随机 */
    private double temperature = 0.7;

    /** 连接超时（毫秒） */
    private int connectTimeout = 30000;

    /** 读取超时（毫秒） */
    private int readTimeout = 60000;

    // ========== Getters & Setters ==========

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public int getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(int connectTimeout) { this.connectTimeout = connectTimeout; }

    public int getReadTimeout() { return readTimeout; }
    public void setReadTimeout(int readTimeout) { this.readTimeout = readTimeout; }
}
