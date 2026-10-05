package org.example.core.workflow.sequential.email.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 邮件工作流专属配置
 * <p>
 * yml 前缀：app.workflow.email.*
 * <p>
 * 引擎级配置（超时/锁/dry-run）在 core 的 WorkflowEngineProperties——这里只放邮件专属的。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.workflow.email")
public class EmailWorkflowProperties {

    /** 发件人域名——比如 example.com */
    private String senderDomain = "example.com";

    /** 收件人白名单——为空则不限制 */
    private java.util.List<String> recipientWhitelist = java.util.List.of();

    /** 邮件主题最大长度 */
    private int maxSubjectLength = 80;

    /** 邮件正文最大字符数 */
    private int maxBodyChars = 3000;

    /** 是否抄送自己（调试用） */
    private boolean ccSelf = false;
}