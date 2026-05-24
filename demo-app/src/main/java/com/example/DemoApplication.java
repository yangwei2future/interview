package com.example;

import com.interview.deepseek.DeepSeekClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 验证 DeepSeek Starter 自动装配是否生效。
 *
 * 启动前确认 application.yml 配置了 deepseek.api-key。
 */
@SpringBootApplication
public class DemoApplication implements CommandLineRunner {

    @Autowired(required = false)
    private DeepSeekClient deepSeekClient;

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @Override
    public void run(String... args) {
        if (deepSeekClient == null) {
            System.out.println("========================================");
            System.out.println("DeepSeekClient 未创建！");
            System.out.println("请检查 application.yml 是否配置了：");
            System.out.println("  deepseek:");
            System.out.println("    api-key: sk-xxxx");
            System.out.println("========================================");
            return;
        }

        System.out.println("========================================");
        System.out.println("DeepSeekClient 注入成功！自动装配生效。");
        System.out.println("========================================");

        String reply = deepSeekClient.chat("说一句鼓励Java程序员的话");
        System.out.println("DeepSeek 回复: " + reply);
    }
}
