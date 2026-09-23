package org.example;


import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

@Slf4j
@SpringBootApplication
public class FirstAgentProjectApplication {

    public static void main(String[] args) {
        SpringApplication.run(FirstAgentProjectApplication.class, args);
        log.info("🚀 FirstAgentProject 已启动！");
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
