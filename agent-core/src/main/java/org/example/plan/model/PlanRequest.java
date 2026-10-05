package org.example.plan.model;

/**
 * 入口请求
 */
public record PlanRequest(
        String userInput,
        String conversationId,
        String fullUserId
) {}