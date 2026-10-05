package org.example.workflow.core.dsl.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 重试配置
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RetryConfig {

    @JsonProperty("max-attempts")
    private Integer maxAttempts;

    @JsonProperty("backoff-ms")
    private Long backoffMs;

    @JsonProperty("max-backoff-ms")
    private Long maxBackoffMs;

    @JsonProperty("policy")
    private String policy;   // FIXED / EXPONENTIAL / EXPONENTIAL_JITTER / IMMEDIATE
}