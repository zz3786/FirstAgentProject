package org.example;


import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.annotation.EnableRetry;

@Slf4j
@SpringBootApplication
@EnableRetry
@MapperScan("org.example.rag.mapper")   // ← 加这个
public class FirstAgentProjectApplication {

    public static void main(String[] args) {
        SpringApplication.run(FirstAgentProjectApplication.class, args);
        log.info("🚀 FirstAgentProject 已启动！");

        // ★ 打印 JVM 的实际工作目录
        System.out.println(">>> JVM 工作目录: " + System.getProperty("user.dir"));
        System.out.println(">>> ./tessdata 绝对路径: "
                + new java.io.File("./tessdata/").getAbsolutePath());
        System.out.println(">>> ./tessdata 是否存在: "
                + new java.io.File("./tessdata/").exists());
    }

    @Bean
    public CommandLineRunner checkBeans(ApplicationContext ctx) {
        return args -> {
            log.info(">>> redisChatClient: {}", ctx.containsBean("redisChatClient"));
            log.info(">>> plainChatClient: {}", ctx.containsBean("plainChatClient"));
            log.info(">>> summarizerClient: {}", ctx.containsBean("summarizerClient"));
            log.info(">>> compactingChatMemoryAdvisor: {}", ctx.containsBean("compactingChatMemoryAdvisor"));
        };
    }

}
