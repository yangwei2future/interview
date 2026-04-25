package com.yangwei.fileupload;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 自动装配入口
 *
 * @AutoConfiguration         = 标记这是自动装配类
 * @EnableConfigurationProperties = 让 FileUploadProperties 生效，读取 yml 配置
 * @ConditionalOnMissingBean   = 对接方自己配了 FileUploadClient 就用他的，没配才用默认的
 */
@AutoConfiguration
@EnableConfigurationProperties(FileUploadProperties.class)
public class FileUploadAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FileUploadClient fileUploadClient(FileUploadProperties properties) {
        System.out.println(">>> FileUploadAutoConfiguration 生效，自动创建 FileUploadClient");
        System.out.println(">>> 读取到配置 env=" + properties.getEnv()
                + ", serverUrl=" + properties.getServerUrl());
        return new FileUploadClient(properties);
    }
}
