package org.example.workflow.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 邮件发送工具——教学环境用日志模拟
 * <p>
 * 生产环境替换为 JavaMailSender 或公司内部邮件网关。
 */
@Slf4j
@Component
public class EmailTools {

    /**
     * 发送邮件（模拟）
     *
     * @return messageId
     */
    public String send(String to, String subject, String body) {
        log.info("""
                ========== 发送邮件 ==========
                收件人：{}
                主题：{}
                正文：
                {}
                ==============================
                """, to, subject, body);

        // 模拟网络延迟
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("发送被中断", e);
        }

        return "msg-" + System.currentTimeMillis();
    }
}