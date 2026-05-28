package com.interview.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Collections;

/**
 * DeepSeek API 调用客户端。
 * 由 DeepSeekAutoConfiguration 自动创建。
 */
public class DeepSeekClient {

    private final DeepSeekProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public DeepSeekClient(DeepSeekProperties properties) {
        this.properties = properties;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 发送单轮对话。
     *
     * @param userMessage 用户输入
     * @return 模型回复内容
     */
    public String chat(String userMessage) {
        return chat("You are a helpful assistant.", userMessage);
    }

    /**
     * 发送多轮对话（带 system prompt）。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户输入
     * @return 模型回复内容
     */
    public String chat(String systemPrompt, String userMessage) {
        System.err.println("[DeepSeekClient] url=" + properties.getBaseUrl()
                + ", model=" + properties.getModel()
                + ", apiKey=" + (properties.getApiKey() != null
                    ? properties.getApiKey().substring(0, 10) + "..." : "NULL"));

        DeepSeekChatRequest.Message systemMsg =
                new DeepSeekChatRequest.Message("system", systemPrompt);
        DeepSeekChatRequest.Message userMsg =
                new DeepSeekChatRequest.Message("user", userMessage);

        DeepSeekChatRequest request = new DeepSeekChatRequest(
                properties.getModel(),
                java.util.Arrays.asList(systemMsg, userMsg),
                properties.getMaxTokens(),
                properties.getTemperature()
        );

        try {
            String url = properties.getBaseUrl() + "/v1/chat/completions";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(properties.getApiKey());

            String body = objectMapper.writeValueAsString(request);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);

            ResponseEntity<DeepSeekChatResponse> response = restTemplate.exchange(
                    URI.create(url),
                    HttpMethod.POST,
                    entity,
                    DeepSeekChatResponse.class
            );

            if (response.getBody() != null) {
                return response.getBody().getContent();
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException("DeepSeek API 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发送原始请求（高级用法，支持多轮对话历史）。
     */
    public DeepSeekChatResponse chat(DeepSeekChatRequest request) {
        try {
            String url = properties.getBaseUrl() + "/v1/chat/completions";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(properties.getApiKey());

            String body = objectMapper.writeValueAsString(request);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);

            ResponseEntity<DeepSeekChatResponse> response = restTemplate.exchange(
                    URI.create(url),
                    HttpMethod.POST,
                    entity,
                    DeepSeekChatResponse.class
            );

            return response.getBody();
        } catch (Exception e) {
            throw new RuntimeException("DeepSeek API 调用失败: " + e.getMessage(), e);
        }
    }
}
