package com.interview.deepseek;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * DeepSeek 自动配置类。
 *
 * 生效条件：
 *   1. classpath 有 DeepSeekClient 类
 *   2. 配置了 deepseek.api-key（不配就不创建 Bean）
 */
@AutoConfiguration
@ConditionalOnClass(DeepSeekClient.class)
@EnableConfigurationProperties(DeepSeekProperties.class)
@ConditionalOnProperty(prefix = "deepseek.api", name = "api-key")
public class DeepSeekAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DeepSeekClient deepSeekClient(DeepSeekProperties properties) {
        return new DeepSeekClient(properties);
    }
}
