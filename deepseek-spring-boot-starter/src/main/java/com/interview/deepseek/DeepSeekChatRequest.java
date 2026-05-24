package com.interview.deepseek;

import java.util.List;

/**
 * DeepSeek Chat 请求模型。
 * 对应 API 文档：POST /v1/chat/completions
 */
public class DeepSeekChatRequest {

    private String model;
    private List<Message> messages;
    private int maxTokens;
    private double temperature;
    private boolean stream;

    public DeepSeekChatRequest() {}

    public DeepSeekChatRequest(String model, List<Message> messages,
                               int maxTokens, double temperature) {
        this.model = model;
        this.messages = messages;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
    }

    public static class Message {
        private String role;    // system / user / assistant
        private String content;

        public Message() {}

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    // ========== Getters & Setters ==========

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public List<Message> getMessages() { return messages; }
    public void setMessages(List<Message> messages) { this.messages = messages; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public boolean isStream() { return stream; }
    public void setStream(boolean stream) { this.stream = stream; }
}
